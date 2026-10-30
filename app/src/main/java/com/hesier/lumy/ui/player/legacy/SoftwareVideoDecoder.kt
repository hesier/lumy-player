@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player.legacy

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.VideoDecoderOutputBuffer
import java.util.ArrayDeque

/** A bounded send/receive queue: one compressed packet may produce zero or several frames. */
internal class SoftwareVideoDecoder(private val format: Format) :
    Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, SoftwareVideoDecoderException> {
    private val lock = Object()
    private val inputs = ArrayDeque<DecoderInputBuffer>()
    private val queuedInputs = ArrayDeque<DecoderInputBuffer>()
    private val outputs = ArrayDeque<SoftwareVideoOutputBuffer>()
    private val queuedOutputs = ArrayDeque<SoftwareVideoOutputBuffer>()
    private var dequeuedInput: DecoderInputBuffer? = null
    private var generation = 0
    private var flushRequested = false
    private var released = false
    private var failure: SoftwareVideoDecoderException? = null
    private var outputStartTimeUs = C.TIME_UNSET
    private var skipped = 0
    private val context = createContext()
    private val worker = Thread(::decodeLoop, "LumySoftwareVideo")

    init {
        repeat(BUFFER_COUNT) {
            inputs.add(DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT))
            outputs.add(SoftwareVideoOutputBuffer { recycleOutput(it as SoftwareVideoOutputBuffer) })
        }
        worker.start()
    }

    override fun getName() = "LumyFFmpeg-${SoftwareVideoRenderer.codecName(format)}"

    override fun setOutputStartTimeUs(outputStartTimeUs: Long) = synchronized(lock) {
        this.outputStartTimeUs = outputStartTimeUs
    }

    override fun dequeueInputBuffer(): DecoderInputBuffer? = synchronized(lock) {
        checkFailure()
        check(dequeuedInput == null)
        inputs.pollFirst().also { dequeuedInput = it }
    }

    override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) = synchronized(lock) {
        checkFailure()
        check(inputBuffer === dequeuedInput)
        if (inputBuffer.isEncrypted || (inputBuffer.data?.remaining() ?: 0) > MAX_PACKET_BYTES) {
            throw SoftwareVideoDecoderException("Encrypted or oversized software video packet")
        }
        queuedInputs.addLast(inputBuffer)
        dequeuedInput = null
        lock.notifyAll()
    }

    override fun dequeueOutputBuffer(): VideoDecoderOutputBuffer? = synchronized(lock) {
        checkFailure()
        queuedOutputs.pollFirst()
    }

    override fun flush() = synchronized(lock) {
        generation++
        flushRequested = true
        skipped = 0
        dequeuedInput?.let(::recycleInput)
        dequeuedInput = null
        while (queuedInputs.isNotEmpty()) recycleInput(queuedInputs.removeFirst())
        while (queuedOutputs.isNotEmpty()) recycleOutput(queuedOutputs.removeFirst())
        lock.notifyAll()
    }

    override fun release() {
        synchronized(lock) {
            released = true
            lock.notifyAll()
        }
        // Never close the native context while the worker still owns it.
        var interrupted = false
        while (worker.isAlive) {
            try {
                worker.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        synchronized(lock) {
            inputs.clear()
            queuedInputs.clear()
            outputs.clear()
            queuedOutputs.clear()
            dequeuedInput = null
        }
    }

    private fun createContext(): Long {
        val codec = SoftwareVideoRenderer.codecName(format)
        if (codec == null || !SoftwareVideoRenderer.isSupportedSize(format.width, format.height) ||
            SoftwareVideoRenderer.isHdr(format) || !NativeVideoDecoder.isAvailable()) {
            throw SoftwareVideoDecoderException("Unsupported software video format: $format")
        }
        val size = format.initializationData.sumOf { it.size.toLong() }
        if (size > MAX_EXTRA_DATA_BYTES) throw SoftwareVideoDecoderException("Oversized video initialization")
        val extra = ByteArray(size.toInt())
        var offset = 0
        for (data in format.initializationData) {
            data.copyInto(extra, offset)
            offset += data.size
        }
        try {
            return NativeVideoDecoder.create(codec, extra, format.width, format.height,
                format.pixelWidthHeightRatio, format.colorInfo?.colorSpace ?: Format.NO_VALUE,
                format.colorInfo?.colorRange ?: Format.NO_VALUE).also {
                if (it == 0L) throw SoftwareVideoDecoderException("FFmpeg video initialization failed")
            }
        } catch (error: Exception) {
            throw SoftwareVideoDecoderException("FFmpeg video initialization failed", error)
        }
    }

    private fun decodeLoop() {
        var needsDrain = false
        try {
            while (true) {
                val task = synchronized(lock) {
                    while (!released && !flushRequested &&
                        (outputs.isEmpty() || (!needsDrain && queuedInputs.isEmpty()))) lock.wait()
                    if (released) return
                    if (flushRequested) {
                        flushRequested = false
                        NativeVideoDecoder.flush(context)
                        needsDrain = false
                    }
                    if (outputs.isEmpty() || (!needsDrain && queuedInputs.isEmpty())) null else {
                        Work(generation, outputs.removeFirst(), if (needsDrain) null else queuedInputs.removeFirst())
                    }
                } ?: continue
                needsDrain = decodeStep(task)
            }
        } catch (error: Exception) {
            synchronized(lock) {
                failure = SoftwareVideoDecoderException("FFmpeg video decoding failed", error)
            }
        } catch (error: OutOfMemoryError) {
            synchronized(lock) {
                failure = SoftwareVideoDecoderException("FFmpeg video allocation failed", error)
            }
        } finally {
            NativeVideoDecoder.close(context)
        }
    }

    private fun decodeStep(task: Work): Boolean {
        var status: Int
        try {
            task.input?.let { input ->
                val data = input.data
                val result = NativeVideoDecoder.send(context, data, data?.position() ?: 0,
                    data?.remaining() ?: 0, input.timeUs, input.isEndOfStream)
                if (result < 0) throw decodeError("send", result)
            }
            status = NativeVideoDecoder.receive(context, task.output)
            if (status < 0) throw decodeError("receive", status)
        } finally {
            synchronized(lock) { task.input?.let(::recycleInput) }
        }
        synchronized(lock) {
            when {
                task.generation != generation || released || status == RECEIVE_AGAIN -> recycleOutput(task.output)
                status == RECEIVE_EOF -> {
                    task.output.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                    queuedOutputs.addLast(task.output)
                }
                outputStartTimeUs != C.TIME_UNSET && task.output.timeUs < outputStartTimeUs -> {
                    skipped++
                    recycleOutput(task.output)
                }
                else -> {
                    task.output.format = format
                    task.output.skippedOutputBufferCount = skipped
                    skipped = 0
                    queuedOutputs.addLast(task.output)
                }
            }
        }
        return status == RECEIVE_FRAME
    }

    private fun recycleInput(input: DecoderInputBuffer) {
        input.clear()
        inputs.addLast(input)
    }

    private fun recycleOutput(output: SoftwareVideoOutputBuffer) = synchronized(lock) {
        output.clear()
        if (!released) outputs.addLast(output)
        lock.notifyAll()
    }

    private fun checkFailure() {
        failure?.let { throw it }
        check(!released)
    }

    private fun decodeError(operation: String, result: Int) = SoftwareVideoDecoderException(
        "FFmpeg video $operation: ${NativeVideoDecoder.errorString(result)}")

    private data class Work(val generation: Int, val output: SoftwareVideoOutputBuffer, val input: DecoderInputBuffer?)

    companion object {
        private const val BUFFER_COUNT = 4
        private const val MAX_PACKET_BYTES = 16 * 1024 * 1024
        private const val MAX_EXTRA_DATA_BYTES = 1024 * 1024
        private const val RECEIVE_AGAIN = 0
        private const val RECEIVE_FRAME = 1
        private const val RECEIVE_EOF = 2
    }
}

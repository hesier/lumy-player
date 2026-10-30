@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import androidx.media3.common.text.Cue
import androidx.media3.decoder.DecoderInputBuffer

/** Keeps compressed history separate from the at most one active Bitmap decode. */
internal class PgsSnapshotDecoder(
    private val onOutput: () -> Unit,
    private val decode: (ByteArray) -> List<Cue> = { PgsSnapshotCodec.decode(it) },
) {
    data class Sample(
        val sequence: Long,
        val timeUs: Long,
        val offsetUs: Long,
        val data: ByteArray,
        val selectionTimeUs: Long = timeUs,
    )
    private data class Request(val generation: Long, val sample: Sample)

    private val lock = Object()
    private var generation = 0L
    private var requestedSequence = -1L
    private var pending: Request? = null
    private var output: PgsSnapshotOutputBuffer? = null
    private var released = false
    private val worker = Thread(::run, "LumyPgsSnapshot").apply { start() }

    fun request(sample: Sample) = synchronized(lock) {
        if (released || requestedSequence == sample.sequence) return@synchronized
        requestedSequence = sample.sequence
        pending = Request(generation, sample)
        output = null
        lock.notifyAll()
    }

    fun poll(): PgsSnapshotOutputBuffer? = synchronized(lock) {
        output.also { output = null }
    }

    fun flush() = synchronized(lock) {
        generation++
        requestedSequence = -1
        pending = null
        output = null
    }

    fun release() {
        synchronized(lock) {
            released = true
            pending = null
            output = null
            lock.notifyAll()
        }
        var interrupted = false
        while (worker.isAlive) {
            try {
                worker.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun run() {
        while (true) {
            val request = synchronized(lock) {
                while (!released && pending == null) lock.wait()
                if (released) return
                checkNotNull(pending).also { pending = null }
            }
            val decoded = decode(request.sample)
            val accepted = synchronized(lock) {
                if (released || request.generation != generation ||
                    request.sample.sequence != requestedSequence) false else {
                    output = decoded
                    true
                }
            }
            if (accepted) onOutput()
        }
    }

    private fun decode(sample: Sample): PgsSnapshotOutputBuffer = try {
        PgsSnapshotOutputBuffer(sample, decode(sample.data))
    } catch (error: RuntimeException) {
        PgsSnapshotOutputBuffer(sample, emptyList(), error)
    } catch (error: OutOfMemoryError) {
        PgsSnapshotOutputBuffer(sample, emptyList(), error)
    }
}

/** Reject oversized samples before SampleQueue can grow the renderer's scratch buffer. */
internal class PgsSnapshotDecoderInputBuffer :
    DecoderInputBuffer(BUFFER_REPLACEMENT_MODE_NORMAL) {
    override fun ensureSpaceForWrite(length: Int) {
        require(length >= 0 && length.toLong() + (data?.position() ?: 0) <= PgsSnapshotCodec.MAX_SAMPLE_BYTES)
        super.ensureSpaceForWrite(length)
    }
}

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Log
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.text.TextOutput
import java.util.ArrayDeque

/** Snapshot samples are independent, so only the state at the current clock needs Bitmap work. */
internal class PgsSnapshotRenderer(output: TextOutput, outputLooper: Looper) : BaseRenderer(C.TRACK_TYPE_TEXT) {
    private val output = PgsSnapshotOutput(output, outputLooper)
    private var outputGeneration = 0L
    private var input: PgsSnapshotDecoderInputBuffer? = null
    private var decoder: PgsSnapshotDecoder? = null
    private val history = ArrayDeque<PgsSnapshotDecoder.Sample>()
    private var historyBytes = 0L
    private var future: PgsSnapshotDecoder.Sample? = null
    private var streamBoundary: PgsSnapshotDecoder.Sample? = null
    private var streamStartUs = Long.MIN_VALUE
    private var target: PgsSnapshotDecoder.Sample? = null
    private var sequence = 0L
    private var displayedSequence = -1L
    private var inputEnded = false
    private var outputEnded = false
    private var lastPositionUs = 0L
    @Volatile private var wakeupListener: Renderer.WakeupListener? = null

    override fun getName() = "LumyPgsSnapshotRenderer"

    override fun supportsFormat(format: Format): Int = RendererCapabilities.create(
        when {
            format.sampleMimeType != PgsSnapshotCodec.MIME_TYPE -> C.FORMAT_UNSUPPORTED_TYPE
            format.cryptoType != C.CRYPTO_TYPE_NONE -> C.FORMAT_UNSUPPORTED_DRM
            else -> C.FORMAT_HANDLED
        },
    )

    override fun isReady() = true
    override fun isEnded() = outputEnded

    override fun handleMessage(messageType: Int, message: Any?) {
        if (messageType == Renderer.MSG_SET_WAKEUP_LISTENER) {
            wakeupListener = message as? Renderer.WakeupListener
        } else super.handleMessage(messageType, message)
    }

    override fun onStreamChanged(
        formats: Array<Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        // Old-period samples retain their own offsets until the new period actually takes over.
        if (decoder != null) {
            streamBoundary = PgsSnapshotDecoder.Sample(++sequence, startPositionUs, offsetUs,
                PgsSnapshotCodec.encode(null))
            val iterator = history.iterator()
            while (iterator.hasNext()) {
                val sample = iterator.next()
                if (sample.selectionTimeUs >= startPositionUs) {
                    historyBytes -= sample.data.size
                    iterator.remove()
                }
            }
        }
        streamStartUs = if (decoder == null) Long.MIN_VALUE else startPositionUs
        inputEnded = false
        outputEnded = false
        if (decoder == null) decoder = PgsSnapshotDecoder({ wakeupListener?.onWakeup() })
        if (input == null) input = PgsSnapshotDecoderInputBuffer()
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        lastPositionUs = positionUs
        resetState()
        streamStartUs = Long.MIN_VALUE
        decoder?.flush()
        outputGeneration = output.reset(positionUs - streamOffsetUs)
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        lastPositionUs = positionUs
        val decoder = decoder ?: return
        // Paused renderers still read, select and decode the snapshot at the seek position.
        val caughtUp = readThroughPosition(positionUs)
        val selected = history.lastOrNull { it.selectionTimeUs <= positionUs }
        if (target?.sequence != selected?.sequence) {
            target = selected
            if (selected == null) {
                decoder.flush()
                displayedSequence = -1
                output.publish(CueGroup(emptyList(), positionUs - streamOffsetUs), outputGeneration)
            }
        }
        if (caughtUp && selected != null) decoder.request(selected)
        if (caughtUp) decoder.poll()?.let { result ->
            if (result.sample.sequence == target?.sequence) publish(result)
        }
        outputEnded = inputEnded && future == null && caughtUp &&
            (target == null || displayedSequence == target?.sequence)
    }

    private fun readThroughPosition(positionUs: Long): Boolean {
        streamBoundary?.let { boundary ->
            if (positionUs < boundary.timeUs) {
                future?.takeIf { it.selectionTimeUs <= positionUs }?.let { remember(it); future = null }
                return true
            }
            // A previous period's final state must not leak into the new period's initial gap.
            future = null
            remember(boundary)
            streamBoundary = null
        }
        future?.let {
            if (it.selectionTimeUs > positionUs) return true
            remember(it)
            future = null
        }
        if (inputEnded) return true
        val buffer = checkNotNull(input)
        repeat(MAX_READS_PER_RENDER) {
            buffer.clear()
            when (readSource(formatHolder, buffer, 0)) {
                C.RESULT_NOTHING_READ -> return true
                C.RESULT_BUFFER_READ -> {
                    if (buffer.isEndOfStream) {
                        inputEnded = true
                        return true
                    }
                    buffer.flip()
                    val bytes = checkNotNull(buffer.data)
                    val data = ByteArray(bytes.remaining()).also { bytes.get(it) }
                    val sample = PgsSnapshotDecoder.Sample(++sequence, buffer.timeUs, streamOffsetUs,
                        data, maxOf(streamStartUs, buffer.timeUs))
                    if (sample.selectionTimeUs > positionUs) {
                        future = sample
                        return true
                    }
                    remember(sample)
                }
            }
        }
        // Do not flash an expired intermediate state during a long catch-up after seek.
        return false
    }

    private fun remember(sample: PgsSnapshotDecoder.Sample) {
        history.addLast(sample)
        historyBytes += sample.data.size
        val cutoffUs = sample.selectionTimeUs - HISTORY_US
        while (history.size > 1 && (historyBytes > MAX_HISTORY_BYTES ||
                history.size > MAX_HISTORY_SAMPLES ||
                history.elementAt(1).selectionTimeUs <= cutoffUs)) {
            // Keep the state active at the window start unless a hard budget requires eviction.
            historyBytes -= history.removeFirst().data.size
        }
    }

    private fun publish(result: PgsSnapshotOutputBuffer) {
        result.error?.let { Log.w(name, "PGS snapshot decoding failed", it) }
        displayedSequence = result.sample.sequence
        output.publish(CueGroup(result.cues, result.sample.timeUs - result.sample.offsetUs), outputGeneration)
    }

    override fun onDisabled() {
        resetState()
        decoder?.release()
        decoder = null
        input = null
        outputGeneration = output.reset(lastPositionUs - streamOffsetUs)
    }

    override fun onReset() {
        decoder?.release()
        decoder = null
        input = null
        resetState()
        outputGeneration = output.reset(lastPositionUs - streamOffsetUs)
    }

    override fun onRelease() = onReset()

    private fun resetState() {
        history.clear()
        historyBytes = 0
        future = null
        streamBoundary = null
        target = null
        displayedSequence = -1
        inputEnded = false
        outputEnded = false
    }

    companion object {
        private const val MAX_READS_PER_RENDER = 32
        private const val HISTORY_US = 20_000_000L
        private const val MAX_HISTORY_BYTES = 16L * 1024 * 1024
        private const val MAX_HISTORY_SAMPLES = 2048
    }
}

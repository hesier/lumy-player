@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hesier.lumy.ui.player

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import org.json.JSONObject
import kotlin.math.abs

/** Window work and decoder counters are separate from GPU time and presentation cadence. */
internal class DanmakuDeviceBenchmarkStats(private val window: Window) : AnalyticsListener, Player.Listener {
    private val thread = HandlerThread("DanmakuBenchmarkMetrics").apply { start() }
    private val frameGate = Any()
    private val frames = TimingSamples()
    private val vsyncDelay = TimingSamples()
    @Volatile private var collecting = false
    @Volatile private var callbackDrops = 0L
    private val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        synchronized(frameGate) { if (collecting) {
            frames.record(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
            vsyncDelay.record((metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP) -
                metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)).coerceAtLeast(0))
            callbackDrops += dropped
        } }
    }
    var errorCode: String? = null
        private set
    var codecErrorType: String? = null
        private set
    var decoder = ""
        private set
    var inputFormat: Format? = null
        private set
    private var outputWidth = 0
    private var outputHeight = 0
    private var dropped = 0L
    private var processingOffsetUs = 0L
    private var processingCount = 0L
    private var bufferingCount = 0
    private var bufferingTotalMs = 0L
    private var bufferingStartedMs: Long? = null
    private var visibleTotal = 0L
    private var visibleSamples = 0
    private var visibleMax = 0
    private var divisorMax = 1
    private var maxDriftMs = 0L
    private var decoderBaseline = LongArray(6)

    init { window.addOnFrameMetricsAvailableListener(frameListener, Handler(thread.looper)) }

    override fun onPlayerError(error: PlaybackException) { errorCode = error.errorCodeName }

    override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
        codecErrorType = videoCodecError.javaClass.simpleName
    }

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
        initializedTimestampMs: Long, initializationDurationMs: Long) { decoder = decoderName }

    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?) { inputFormat = format }

    override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
        outputWidth = videoSize.width
        outputHeight = videoSize.height
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, count: Int, elapsedMs: Long) {
        if (collecting) dropped += count
    }

    override fun onVideoFrameProcessingOffset(eventTime: AnalyticsListener.EventTime, totalProcessingOffsetUs: Long,
        frameCount: Int) {
        if (collecting) { processingOffsetUs += totalProcessingOffsetUs; processingCount += frameCount }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (!collecting) return
        if (playbackState == Player.STATE_BUFFERING && bufferingStartedMs == null) {
            bufferingCount++
            bufferingStartedMs = SystemClock.elapsedRealtime()
        } else if (playbackState != Player.STATE_BUFFERING) finishBuffering()
    }

    private fun finishBuffering() {
        bufferingStartedMs?.let { bufferingTotalMs += SystemClock.elapsedRealtime() - it }
        bufferingStartedMs = null
    }

    fun validateHardware4k(player: ExoPlayer) {
        val format = player.videoFormat ?: error("Benchmark missing video input format")
        check(format.width >= 3840) { "Benchmark source is not 4K" }
        val mime = format.sampleMimeType ?: error("Benchmark missing video MIME")
        val hardware = MediaCodecUtil.getDecoderInfos(mime, false, false)
            .any { it.name == decoder && it.hardwareAccelerated }
        check(hardware) { "Benchmark requires hardware 4K decoder; decoder=$decoder" }
    }

    fun begin(player: ExoPlayer) = synchronized(frameGate) {
        collecting = false
        frames.reset(); vsyncDelay.reset(); callbackDrops = 0
        dropped = 0; processingOffsetUs = 0; processingCount = 0
        bufferingCount = 0; bufferingTotalMs = 0; bufferingStartedMs = null
        visibleTotal = 0; visibleSamples = 0; visibleMax = 0; divisorMax = 1; maxDriftMs = 0
        decoderBaseline = counters(player)
        collecting = true
    }

    fun sample(overlay: DanmakuOverlay, commentPositionMs: Long, enabled: Boolean) {
        val visible = overlay.visibleCount
        visibleTotal += visible; visibleSamples++; visibleMax = maxOf(visibleMax, visible)
        divisorMax = maxOf(divisorMax, overlay.densityDivisor)
        if (enabled && overlay.appliedConfig != null) {
            maxDriftMs = maxOf(maxDriftMs, abs(overlay.currentTimeMs - commentPositionMs))
        }
    }

    fun stop() { synchronized(frameGate) { collecting = false }; finishBuffering() }

    fun mediaSnapshot(player: ExoPlayer): JSONObject {
        val values = counters(player)
        val elapsedBuffering = bufferingStartedMs?.let { SystemClock.elapsedRealtime() - it } ?: 0
        return JSONObject().put("decoder", decoder).put("inputWidth", inputFormat?.width)
            .put("inputHeight", inputFormat?.height).put("inputFrameRate", inputFormat?.frameRate)
            .put("videoMime", inputFormat?.sampleMimeType).put("outputWidth", outputWidth).put("outputHeight", outputHeight)
            .put("rendered", values[0] - decoderBaseline[0]).put("dropped", values[1] - decoderBaseline[1])
            .put("skipped", values[2] - decoderBaseline[2]).put("analyticsDropped", dropped)
            .put("decoderMaxConsecutiveDroppedSincePlayerCreated", values[3])
            .put("decoderProcessingOffsetCount", values[4] - decoderBaseline[4])
            .put("decoderProcessingOffsetSumUs", values[5] - decoderBaseline[5])
            .put("processingOffsetSumUs", processingOffsetUs).put("processingOffsetCount", processingCount)
            .put("bufferingCount", bufferingCount).put("bufferingMs", bufferingTotalMs + elapsedBuffering)
            .put("visibleAverage", if (visibleSamples == 0) 0.0 else visibleTotal.toDouble() / visibleSamples)
            .put("visibleMax", visibleMax).put("divisorMax", divisorMax).put("maxObservedDriftMs", maxDriftMs)
    }

    fun windowSnapshot(): JSONObject = JSONObject().put("totalDuration", frames.snapshot())
        .put("vsyncMinusIntended", vsyncDelay.snapshot()).put("callbackFramesLost", callbackDrops)

    private fun counters(player: ExoPlayer): LongArray {
        val counters = player.videoDecoderCounters ?: return LongArray(6)
        counters.ensureUpdated()
        return longArrayOf(counters.renderedOutputBufferCount.toLong(), counters.droppedBufferCount.toLong(),
            counters.skippedOutputBufferCount.toLong(), counters.maxConsecutiveDroppedBufferCount.toLong(),
            counters.videoFrameProcessingOffsetCount.toLong(), counters.totalVideoFrameProcessingOffsetUs)
    }

    fun release() {
        collecting = false
        window.removeOnFrameMetricsAvailableListener(frameListener)
        thread.quitSafely()
    }

    private class TimingSamples {
        private val values = LongArray(20_000)
        private var stored = 0
        private var total = 0L
        private var over16 = 0L
        private var over33 = 0L
        private var over50 = 0L
        @Synchronized fun record(ns: Long) {
            if (ns < 0) return
            total++
            if (stored < values.size) values[stored++] = ns
            if (ns > 16_666_667) over16++
            if (ns > 33_333_333) over33++
            if (ns > 50_000_000) over50++
        }
        @Synchronized fun reset() { stored = 0; total = 0; over16 = 0; over33 = 0; over50 = 0 }
        fun snapshot(): JSONObject {
            val copy: LongArray
            val result: JSONObject
            synchronized(this) {
                copy = values.copyOf(stored)
                result = JSONObject().put("count", total).put("stored", stored).put("distributionTruncated", total > stored)
                    .put("over16_7Ms", over16).put("over33_3Ms", over33).put("over50Ms", over50)
            }
            copy.sort()
            fun percentile(fraction: Double): Any = if (copy.isEmpty()) JSONObject.NULL else copy[((copy.size - 1) * fraction).toInt()] / 1e6
            return result.put("p50Ms", percentile(0.50)).put("p95Ms", percentile(0.95))
                .put("p99Ms", percentile(0.99)).put("maxStoredMs", (copy.lastOrNull() ?: 0) / 1e6)
        }
    }
}

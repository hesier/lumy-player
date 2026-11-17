package com.hesier.lumy.util.diagnostics

import android.content.Context
import android.os.Build
import androidx.media3.common.Format
import com.hesier.lumy.BuildConfig
import com.hesier.lumy.LumyApp
import com.hesier.lumy.ui.player.DeviceProfile
import com.hesier.lumy.core.AudioStream
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.PlaybackMode
import com.hesier.lumy.net.DiagnosticJournal
import com.hesier.lumy.net.DiagnosticJournal.Event
import com.hesier.lumy.net.DiagnosticJournal.Label
import com.hesier.lumy.net.DiagnosticJournal.Metric
import com.hesier.lumy.ui.player.Plan
import com.hesier.lumy.ui.player.PlaybackIssue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Independent of debug logs, and available in release builds. */
object PlaybackDiagnostics {
    private lateinit var journal: DiagnosticJournal
    fun init(context: Context) { journal = DiagnosticJournal(File(context.filesDir, "playback-diagnostics.json")) }
    fun record(event: Event, metrics: Map<Metric, Long> = emptyMap(), labels: Map<Label, String> = emptyMap()) {
        if (::journal.isInitialized) journal.record(event, metrics, labels)
    }

    fun plan(version: MediaVersion, audio: AudioStream?, plan: Plan) {
        val video = version.video
        val transcode = plan.mode as? PlaybackMode.Transcode
        val metrics = mapOf(
            Metric.WIDTH to video?.width?.toLong(), Metric.HEIGHT to video?.height?.toLong(),
            Metric.BITRATE_KBPS to version.bitrateKbps?.toLong(), Metric.BIT_DEPTH to video?.bitDepth?.toLong(),
            Metric.FRAME_RATE_MILLI to video?.frameRate?.times(1000)?.toLong(),
            Metric.DOVI_PROFILE to video?.doviProfile?.toLong(), Metric.CHANNELS to audio?.channels?.toLong(),
            Metric.SAMPLE_RATE to audio?.sampleRate?.toLong(), Metric.MAX_HEIGHT to transcode?.maxHeight?.toLong(),
            Metric.MAX_BITRATE_KBPS to transcode?.maxBitrateKbps?.toLong(),
            Metric.COPY_VIDEO to transcode?.copyVideo?.let { if (it) 1L else 0L },
            Metric.COPY_AUDIO to transcode?.copyAudio?.let { if (it) 1L else 0L },
            Metric.SDR to transcode?.sdr?.let { if (it) 1L else 0L },
            Metric.REASON_MASK to plan.reasons.fold(0L) { mask, issue -> mask or (1L shl reasonCode(issue)) },
        ).mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
        record(Event.PLAN, metrics, mapOf(
            Label.MODE to if (transcode == null) "direct" else "transcode",
            Label.VIDEO_CODEC to video?.codec.orEmpty(), Label.AUDIO_CODEC to audio?.codec.orEmpty(),
            Label.CONTAINER to version.container.orEmpty(), Label.HDR to (video?.hdr?.name ?: "NONE"),
        ))
    }

    fun format(format: Format, video: Boolean) {
        record(if (video) Event.VIDEO_FORMAT else Event.AUDIO_FORMAT, mapOf(
            Metric.WIDTH to format.width.toLong(), Metric.HEIGHT to format.height.toLong(),
            Metric.BITRATE_KBPS to (format.bitrate / 1000).toLong(),
            Metric.CHANNELS to format.channelCount.toLong(), Metric.SAMPLE_RATE to format.sampleRate.toLong(),
        ), mapOf(Label.MIME to format.sampleMimeType.orEmpty()))
    }

    fun export(): ByteArray = JSONObject().apply {
        val app = LumyApp.instance
        val allowSoftware = app.settings.allowSoftwareVideoDecoding
        val device = DeviceProfile(app, allowSoftwareVideo = allowSoftware)
        put("schemaVersion", 1)
        put("serverKind", app.currentEntry?.kind?.name ?: "NONE")
        put("allowSoftwareVideoDecoding", allowSoftware)
        put("displaySupportsHdr", device.displaySupportsHdr)
        put("hdrEnabled", app.settings.displaySupportsHdr)
        put("videoCapabilities", JSONObject().apply {
            mapOf("h264" to "video/avc", "hevc" to "video/hevc", "av1" to "video/av01", "vp9" to "video/x-vnd.on2.vp9").forEach { (codec, mime) ->
                put(codec, JSONObject().apply {
                    put("hasDecoder", device.hasDecoder(mime))
                    put("hasHardwareDecoder", device.hasHardwareDecoder(mime))
                    put("supportsTenBit", device.supportsTenBit(mime))
                })
            }
        })
        put("appVersion", BuildConfig.VERSION_NAME)
        put("androidSdk", Build.VERSION.SDK_INT)
        put("androidRelease", Build.VERSION.RELEASE)
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("reasonMaskBits", JSONArray(listOf(
            "videoDecodeFailed", "directPlayFailed", "unseekable", "qualityLimit", "container",
            "unknownVideoCodec", "noVideoDecoder", "videoTooDemanding", "unknownAudioCodec",
            "noAudioDecoder", "bandwidthLimit", "outputBitrateLimit", "hdrUnsupported", "subtitleNeedsBurn",
        )))
        put("events", JSONArray(if (::journal.isInitialized) journal.snapshot() else "[]"))
    }.toString(2).toByteArray(Charsets.UTF_8)

    // Stable bit positions encode issue types without their potentially sensitive parameters.
    private fun reasonCode(issue: PlaybackIssue): Int = when (issue) {
        PlaybackIssue.VideoDecodeFailed -> 0
        PlaybackIssue.DirectPlayFailed -> 1
        PlaybackIssue.Unseekable -> 2
        is PlaybackIssue.QualityLimit -> 3
        is PlaybackIssue.Container -> 4
        is PlaybackIssue.UnknownVideoCodec -> 5
        is PlaybackIssue.NoVideoDecoder -> 6
        is PlaybackIssue.VideoTooDemanding -> 7
        is PlaybackIssue.UnknownAudioCodec -> 8
        is PlaybackIssue.NoAudioDecoder -> 9
        is PlaybackIssue.BandwidthLimit -> 10
        is PlaybackIssue.OutputBitrateLimit -> 11
        PlaybackIssue.HdrUnsupported -> 12
        is PlaybackIssue.SubtitleNeedsBurn -> 13
    }
}

package com.hesier.lumy.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A deliberately closed schema: never pass log messages, URLs or media identifiers here. */
class DiagnosticJournal(private val file: File) {
    enum class Event { PLAN, STATE, ERROR, VIDEO_DECODER, AUDIO_DECODER, VIDEO_FORMAT, AUDIO_FORMAT, FALLBACK, STOP }
    enum class Metric {
        POSITION_MS, DURATION_MS, STATE, IS_PLAYING, ERROR_CODE, WIDTH, HEIGHT, BITRATE_KBPS, FRAME_RATE_MILLI,
        BIT_DEPTH, DOVI_PROFILE, CHANNELS, SAMPLE_RATE, DROPPED_FRAMES, MAX_HEIGHT, MAX_BITRATE_KBPS,
        COPY_VIDEO, COPY_AUDIO, SDR, SOFTWARE_VIDEO, HEVC_ONLY, REASON_MASK,
    }
    enum class Label { MODE, VIDEO_CODEC, AUDIO_CODEC, CONTAINER, HDR, DECODER, MIME, FALLBACK }
    @Serializable data class Record(
        val timeMs: Long,
        val event: Event,
        val metrics: Map<Metric, Long>,
        val labels: Map<Label, String>,
    )

    private val records = ArrayDeque<Record>()
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "LumyDiagnostics").apply { isDaemon = true }
    }
    private var pending = false
    private val restored = CountDownLatch(1)

    init {
        worker.execute {
            val previous = runCatching {
                if (file.length() !in 1..MAX_BYTES.toLong()) emptyList()
                else json.decodeFromString<List<Record>>(file.readText()).takeLast(MAX_RECORDS)
            }.getOrDefault(emptyList())
            synchronized(this) {
                val current = records.toList()
                records.clear()
                (previous + current).takeLast(MAX_RECORDS).forEach {
                    records.addLast(it.copy(labels = clean(it.labels)))
                }
            }
            restored.countDown()
        }
    }

    @Synchronized fun record(event: Event, metrics: Map<Metric, Long> = emptyMap(), labels: Map<Label, String> = emptyMap()) {
        records.addLast(Record(System.currentTimeMillis(), event, metrics.toMap(), clean(labels)))
        while (records.size > MAX_RECORDS) records.removeFirst()
        if (!pending) {
            pending = true
            worker.schedule({ persist() }, 250, TimeUnit.MILLISECONDS)
        }
    }

    fun snapshot(): String {
        restored.await()
        return synchronized(this) { json.encodeToString(records.toList()) }
    }

    private fun persist() {
        val bytes = synchronized(this) { pending = false; json.encodeToString(records.toList()) }
        runCatching {
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(bytes)
            temporary.renameTo(file)
        }
    }

    companion object {
        const val MAX_RECORDS = 160
        const val MAX_BYTES = 256 * 1024
        private val json = Json { ignoreUnknownKeys = true }
        private val codecs = setOf("h264", "avc", "hevc", "h265", "av1", "vp9", "vp8", "mpeg2video", "mpeg4", "vc1", "aac", "ac3", "eac3", "dts", "dca", "truehd", "flac", "opus", "vorbis", "mp3", "pcm", "alac")
        private fun clean(labels: Map<Label, String>): Map<Label, String> = labels.mapValues { (key, value) ->
            val allowed = when (key) {
                Label.MODE -> value in setOf("direct", "transcode")
                Label.VIDEO_CODEC, Label.AUDIO_CODEC -> value in codecs
                Label.CONTAINER -> value in setOf("mkv", "mp4", "mov", "avi", "ts", "m2ts", "mpegts", "webm", "hls", "mpeg", "flv")
                Label.HDR -> value in setOf("NONE", "HDR10", "HDR10_PLUS", "HLG", "DOLBY_VISION")
                Label.MIME -> value in setOf("video/avc", "video/hevc", "video/dolby-vision", "video/av01", "video/x-vnd.on2.vp9", "video/x-vnd.on2.vp8", "video/mpeg2", "audio/mp4a-latm", "audio/ac3", "audio/eac3", "audio/vnd.dts", "audio/vnd.dts.hd", "audio/true-hd", "audio/flac", "audio/opus", "audio/raw")
                // Decoder names come exclusively from Media3 renderer callbacks, never media metadata.
                Label.DECODER -> value.length <= 120 && Regex("(?:OMX\\.|c2\\.|LumyFFmpeg-|ffmpeg)[a-zA-Z0-9_.-]*").matches(value)
                Label.FALLBACK -> value in setOf("network", "software", "hevc", "transcode", "unseekable")
            }
            if (allowed) value else "unknown"
        }
    }
}

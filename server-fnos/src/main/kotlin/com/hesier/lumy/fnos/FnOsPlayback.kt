package com.hesier.lumy.fnos

import com.hesier.lumy.core.HdrType
import com.hesier.lumy.core.PlaybackMode
import com.hesier.lumy.core.PlaybackRequest
import com.hesier.lumy.core.PlaybackSource
import com.hesier.lumy.core.ExternalSubtitle
import com.hesier.lumy.core.ServerSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

internal class FnOsPlayback(
    private val session: ServerSession,
    private val client: FnOsClient,
    private val native: FnOsNativeClient,
) {
    private val mutex = Mutex()
    private val links = mutableMapOf<String, String>()

    suspend fun source(request: PlaybackRequest): PlaybackSource {
        val direct = directSource(request)
        val mode = request.mode as? PlaybackMode.Transcode ?: return direct
        return mutex.withLock {
            stopLocked(request.sessionId)
            val body = playbackBody(request, mode)
            // Keep the response and its cleanup handle even if cancellation races with HTTP completion.
            try {
                val result = withContext(NonCancellable) {
                    val result = native.post("play/play", body) as? JsonObject
                        ?: throw IOException("Invalid fnOS playback response")
                    val link = result.text("play_link") ?: throw IOException("fnOS play link is missing")
                    links[request.sessionId] = link
                    result
                }
                currentCoroutineContext().ensureActive()
                val warning = result["non_fatal_errno"]
                if (warning != null && warning != JsonNull && warning != JsonPrimitive(0) &&
                    warning != JsonPrimitive("") && warning != JsonArray(emptyList())) {
                    throw IOException("fnOS playback reported conversion errors")
                }
                PlaybackSource(
                    urls = listOf(playbackUrl(links.getValue(request.sessionId))),
                    isHls = true,
                    externalSubtitles = if (mode.burnSubtitleId == null) direct.externalSubtitles else emptyList(),
                    startPart = 0,
                    startOffsetMs = request.startMs,
                )
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    runCatching { stopWithRetry(request.sessionId) }.exceptionOrNull()?.let(error::addSuppressed)
                }
                throw error
            }
        }
    }

    private fun directSource(request: PlaybackRequest): PlaybackSource {
        val base = session.baseUrl.trimEnd('/') + "/"
        fun url(path: String) = base.toHttpUrl().resolve(path)!!.newBuilder()
            .addQueryParameter("ApiKey", session.token)
        return PlaybackSource(
            urls = listOf(url("Videos/${request.item.id}/stream")
                .addQueryParameter("Static", "true")
                .addQueryParameter("MediaSourceId", request.version.id)
                .addQueryParameter("PlaySessionId", request.sessionId)
                .addQueryParameter("DeviceId", session.deviceId ?: client.info.deviceId)
                .build().toString()),
            isHls = false,
            externalSubtitles = request.version.subtitles.filter { it.external }.map {
                ExternalSubtitle(streamId = it.id, url = url(it.key!!).build().toString(), codec = it.codec,
                    language = it.language, label = it.title)
            },
            startPart = 0,
            startOffsetMs = request.startMs,
        )
    }

    suspend fun stop(request: PlaybackRequest) = mutex.withLock { stopWithRetry(request.sessionId) }

    private suspend fun stopWithRetry(sessionId: String) {
        repeat(STOP_ATTEMPTS) { attempt ->
            try {
                stopLocked(sessionId)
                return
            } catch (error: IOException) {
                if (attempt == STOP_ATTEMPTS - 1) throw error
                delay(STOP_RETRY_MS)
            }
        }
    }

    private suspend fun stopLocked(sessionId: String) {
        val link = links[sessionId] ?: return
        val result = native.post("media/p", buildJsonObject {
            put("req", "media.quit")
            put("reqid", sessionId)
            put("playLink", link)
        }) as? JsonObject
        if (result?.text("result") != "succ") throw IOException("fnOS playback stop was not accepted")
        links.remove(sessionId)
    }

    private suspend fun playbackBody(request: PlaybackRequest, mode: PlaybackMode.Transcode): JsonObject {
        val streams = native.post("stream", buildJsonObject {
            put("media_guid", request.item.id)
            put("ip", session.deviceId ?: "Lumy")
            put("header", buildJsonObject { put("User-Agent", buildJsonArray { add(JsonPrimitive("Lumy")) }) })
            put("level", 1)
        }) as? JsonObject ?: throw IOException("Invalid fnOS stream response")
        val video = streams["video_stream"] as? JsonObject ?: throw IOException("fnOS video stream is missing")
        val audio = selectTrack(streams.objects("audio_streams"), request.audioStreamId, "audio")
        val selectedText = request.version.subtitles.firstOrNull { it.id == request.subtitleStreamId }
            ?.takeIf { !it.external && it.codec.lowercase() in TEXT_SUBTITLES }?.id
        val subtitle = (mode.burnSubtitleId ?: selectedText)?.let {
            selectTrack(streams.objects("subtitle_streams"), it, "subtitle")?.text("guid")
                ?: throw IOException("fnOS subtitle is missing")
        }
        val quality = selectQuality(streams.objects("qualities"), mode)
        return buildJsonObject {
            put("media_guid", request.item.id)
            put("video_guid", video.text("guid") ?: throw IOException("fnOS video GUID is missing"))
            // The native protocol has no verified explicit stream-copy switch.
            put("video_encoder", "h264")
            put("audio_encoder", audioEncoder(audio, mode))
            put("channels", if ((audio?.number("channels") ?: 0) >= SURROUND_CHANNELS) SURROUND_CHANNELS else STEREO_CHANNELS)
            put("audio_guid", audio?.text("guid").orEmpty())
            put("subtitle_guid", subtitle ?: "_no_display_")
            put("resolution", quality.text("resolution")!!)
            put("bitrate", quality.number("bitrate")!!)
            put("startTimestamp", request.startMs.coerceAtLeast(0) / MILLIS_PER_SECOND)
            if (mode.sdr && request.version.video?.hdr?.let { it != HdrType.NONE } == true) put("forced_sdr", 1)
        }
    }

    private fun audioEncoder(audio: JsonObject?, mode: PlaybackMode.Transcode): String {
        val codec = audio?.text("codec_name")?.lowercase()
        // Matching AC3/EAC3 encoders preserve the original packets in native HLS.
        return codec?.takeIf { mode.copyAudio && it in HLS_AUDIO_CODECS } ?: "aac"
    }

    private fun selectTrack(tracks: List<JsonObject>, id: String?, prefix: String): JsonObject? {
        if (id == null) return tracks.firstOrNull { it.number("is_default") == 1 } ?: tracks.firstOrNull()
        tracks.firstOrNull { it.text("guid") == id }?.let { return it }
        val ordinal = id.removePrefix("$prefix:").takeIf { id.startsWith("$prefix:") }?.toIntOrNull()
        if (ordinal != null) return tracks.getOrNull(ordinal) ?: throw IOException("fnOS track is missing")
        return tracks.firstOrNull { it.number("index")?.toString() == id }
            ?: throw IOException("fnOS track is missing")
    }

    private fun selectQuality(qualities: List<JsonObject>, mode: PlaybackMode.Transcode): JsonObject =
        qualities.filter { quality ->
            val height = resolutionHeight(quality.text("resolution"))
            val bitrate = quality.number("bitrate")
            height != null && bitrate != null && bitrate > 0 &&
                (mode.maxHeight == null || height <= mode.maxHeight!!) &&
                (mode.maxBitrateKbps == null || bitrate.toLong() <= mode.maxBitrateKbps!!.toLong() * MILLIS_PER_SECOND)
        }.maxWithOrNull(compareBy<JsonObject> { resolutionHeight(it.text("resolution")) }
            .thenBy { it.number("bitrate") }) ?: throw IOException("No fnOS quality meets playback limits")

    private fun resolutionHeight(value: String?): Int? = when (value?.lowercase()) {
        "8k" -> 4320
        "4k" -> 2160
        else -> value?.removeSuffix("p")?.toIntOrNull()
    }

    private fun playbackUrl(link: String): String {
        val base = session.baseUrl.trimEnd('/').toHttpUrl()
        val url = base.resolve(link) ?: throw IOException("Invalid fnOS play link")
        if (url.scheme != base.scheme || url.host != base.host || url.port != base.port ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) {
            throw IOException("Invalid fnOS play link origin")
        }
        return url.toString()
    }

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
        const val STEREO_CHANNELS = 2
        // Native AAC output is limited to 5.1 even when channels=8 is requested.
        const val SURROUND_CHANNELS = 6
        val HLS_AUDIO_CODECS = setOf("aac", "ac3", "eac3")
        const val STOP_ATTEMPTS = 3
        const val STOP_RETRY_MS = 250L
        val TEXT_SUBTITLES = setOf("srt", "ass", "ssa", "vtt", "subrip", "webvtt")
    }
}

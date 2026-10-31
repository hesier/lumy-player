package com.hesier.lumy.fnos

import com.hesier.lumy.core.PlaybackRequest
import com.hesier.lumy.core.PlaybackState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.IOException

internal data class FnOsResume(
    val itemId: String,
    val mediaId: String,
    val positionMs: Long,
    val durationMs: Long?,
)

internal class FnOsProgress(private val native: FnOsNativeClient) {
    private val mutex = Mutex()
    private val contexts = mutableMapOf<String, Context>()

    suspend fun report(request: PlaybackRequest, state: PlaybackState, positionMs: Long, durationMs: Long) {
        mutex.withLock {
            val context = contexts[request.sessionId] ?: context(request).also { contexts[request.sessionId] = it }
            val duration = if (durationMs > 0) durationMs / MILLIS_PER_SECOND else context.durationSeconds?.toLong()
            val record = JsonObject(buildMap {
                putAll(context.tracks)
                request.version.audio.firstOrNull { it.id == request.audioStreamId && !it.id.startsWith("audio:") }
                    ?.let { put("audio_guid", JsonPrimitive(it.id)) }
                if (request.version.subtitles.any { !it.id.startsWith("subtitle:") }) {
                    val subtitle = request.version.subtitles.firstOrNull { it.id == request.subtitleStreamId }
                    put("subtitle_guid", JsonPrimitive(subtitle?.id ?: "_no_display_"))
                }
                put("item_guid", JsonPrimitive(context.itemId))
                put("media_guid", JsonPrimitive(context.mediaId))
                put("ts", JsonPrimitive(positionMs.coerceAtLeast(0) / MILLIS_PER_SECOND))
                duration?.let { put("duration", JsonPrimitive(it)) }
            })
            if (native.post("play/record", record) != JsonPrimitive(true)) {
                throw IOException("fnOS playback record was not accepted")
            }
            if (state == PlaybackState.STOPPED) contexts.remove(request.sessionId)
        }
    }

    suspend fun resume(): List<FnOsResume> {
        val records = native.get("play/list") as? JsonArray ?: throw IOException("Invalid fnOS playback list")
        return records.mapNotNull { element ->
            val record = element as? JsonObject ?: return@mapNotNull null
            val itemId = record.text("guid") ?: return@mapNotNull null
            val position = record.seconds("ts")?.takeIf { it > 0 } ?: return@mapNotNull null
            val duration = record.seconds("duration")?.takeIf { it > 0 }
            val watched = record["watched"] as? JsonPrimitive
            if (watched?.booleanOrNull == true || watched?.doubleOrNull == 1.0 ||
                (duration != null && position >= duration)) return@mapNotNull null
            val info = if (record.text("media_guid") == null) playInfo(itemId) else null
            val mediaId = record.text("media_guid") ?: info?.text("media_guid") ?: return@mapNotNull null
            val resolvedDuration = duration ?: (info?.get("item") as? JsonObject)?.seconds("duration")?.takeIf { it > 0 }
            if (resolvedDuration != null && position >= resolvedDuration) return@mapNotNull null
            FnOsResume(itemId, mediaId, (position * MILLIS_PER_SECOND).toLong(),
                resolvedDuration?.let { (it * MILLIS_PER_SECOND).toLong() })
        }
    }

    private suspend fun context(request: PlaybackRequest): Context {
        // MediaSource.id identifies the native item; BaseItem.id identifies its file.
        val info = playInfo(request.version.id)
        return Context(
            itemId = info.text("guid") ?: throw IOException("fnOS playback item ID is missing"),
            mediaId = request.item.id,
            durationSeconds = (info["item"] as? JsonObject)?.seconds("duration")?.takeIf { it > 0 },
            tracks = info.filterKeys { it in TRACK_KEYS },
        )
    }

    private suspend fun playInfo(itemId: String): JsonObject =
        native.post("play/info", JsonObject(mapOf("item_guid" to JsonPrimitive(itemId)))) as? JsonObject
            ?: throw IOException("Invalid fnOS playback info")

    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.seconds(key: String): Double? =
        (get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

    private data class Context(
        val itemId: String,
        val mediaId: String,
        val durationSeconds: Double?,
        val tracks: Map<String, JsonElement>,
    )

    companion object {
        private const val MILLIS_PER_SECOND = 1000L
        private val TRACK_KEYS = setOf("video_guid", "audio_guid", "subtitle_guid")
    }
}

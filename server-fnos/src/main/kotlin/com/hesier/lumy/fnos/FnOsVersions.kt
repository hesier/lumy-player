package com.hesier.lumy.fnos

import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.PlaybackRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Native files share a compatibility source ID, so use the file ID for version selection. */
internal class FnOsVersions(
    private val native: FnOsNativeClient,
    private val loadItem: suspend (String) -> BaseItem,
    private val deviceId: String,
) {
    suspend fun load(item: BaseItem, info: JsonObject?): List<MediaVersion> {
        if (item.mediaSources.isEmpty()) return emptyList()
        val canonicalId = item.mediaSources.first().id
        val files = try {
            (native.get("media/itemfile/$canonicalId") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.text("media_guid") }.distinct()
        } catch (error: FnOsHttpException) {
            if (error.code !in listOf(404, 501)) throw error
            emptyList()
        }
        val fileIds = (listOf(item.id) + files).distinct()
        return fileIds.flatMap { fileId ->
            val source = if (fileId == item.id) item else loadItem(fileId)
            val selection = if (files.isEmpty()) info else native.post("play/info", buildJsonObject {
                put("item_guid", canonicalId)
                put("media_guid", fileId)
            }) as? JsonObject
            val streams = native.post("stream", buildJsonObject {
                put("media_guid", fileId)
                put("level", 1)
                put("ip", deviceId)
                put("header", JsonObject(mapOf("User-Agent" to JsonArray(listOf(JsonPrimitive("Lumy"))))))
            }) as JsonObject
            source.fnOsDetail().versions.map { version ->
                version.withFnOsStreams(streams).withFnOsStreamSelection(selection)
                    .let { if (files.isEmpty()) it else it.copy(id = fileId) }
            }
        }.mapIndexed { index, version -> version.copy(index = index) }
    }
}

// Parts retain the canonical source ID even when the version ID identifies a native file.
internal val MediaVersion.fnOsItemId: String get() = parts.firstOrNull()?.id ?: id

internal val PlaybackRequest.fnOsFileId: String
    get() = version.parts.firstOrNull()?.key?.removePrefix("Videos/")?.substringBefore('/') ?: item.id

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

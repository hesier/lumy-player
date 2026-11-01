package com.hesier.lumy.fnos

import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.Page
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException

internal class FnOsFavorites(private val native: FnOsNativeClient) {
    suspend fun page(type: MediaType, offset: Int, limit: Int): Page<JsonObject> {
        require(offset >= 0 && limit > 0)
        val nativeType = when (type) {
            MediaType.MOVIE -> "Movie"
            MediaType.SHOW -> "TV"
            MediaType.EPISODE -> "Episode"
            else -> throw IllegalArgumentException("Unsupported favorite type: $type")
        }
        val page = offset / limit + 1
        val skip = offset % limit
        suspend fun fetch(number: Int) = native.post("favorite/list", buildJsonObject {
            put("page", number)
            put("page_size", limit)
            put("tags", buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive(nativeType)))) })
            put("sort_column", "create_time")
            put("sort_type", "DESC")
        }).jsonObject
        val first = fetch(page)
        val rows = first.rows().toMutableList()
        val total = first["total"]?.jsonPrimitive?.intOrNull ?: rows.size
        if (skip > 0 && rows.size == limit && page * limit < total) rows += fetch(page + 1).rows()
        return Page(rows.drop(skip).take(limit), offset, total)
    }

    suspend fun playableId(row: JsonObject): String {
        val canonicalId = row.getValue("guid").jsonPrimitive.content
        if (row["type"]?.jsonPrimitive?.content == "TV") return canonicalId
        // video_guid names a stream, while compatibility Items requires the media file GUID.
        val info = native.post("play/info", buildJsonObject { put("item_guid", canonicalId) }).jsonObject
        return info["media_guid"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: throw IOException("fnOS favorite has no playable media")
    }

    suspend fun set(id: String, favorite: Boolean) {
        val body = buildJsonObject { put("item_guid", id) }
        // The native frontend treats code=0 as success, including idempotent data=false responses.
        if (favorite) native.put("item/favorite", body) else native.delete("item/favorite", body)
    }

    private fun JsonObject.rows() = (this["list"] as? JsonArray).orEmpty().map { it.jsonObject }
}

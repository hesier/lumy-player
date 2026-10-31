package com.hesier.lumy.fnos

import com.hesier.lumy.core.FilterOption
import com.hesier.lumy.core.LibraryQuery
import com.hesier.lumy.core.Page
import com.hesier.lumy.core.ResolutionFilter
import com.hesier.lumy.core.SortOrder
import com.hesier.lumy.core.WatchFilter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Native queries select canonical IDs; compatibility items retain playable file IDs and image routes. */
internal class FnOsQueries(private val native: FnOsNativeClient) {
    suspend fun page(libraryId: String, offset: Int, limit: Int, query: LibraryQuery): Page<JsonObject> {
        require(offset >= 0 && limit > 0)
        val pageNumber = offset / limit + 1
        val skip = offset % limit
        val first = native.post("item/list", parameters(libraryId, query, pageNumber, limit)).jsonObject
        val rows = first.rows().toMutableList()
        val total = first["total"]?.jsonPrimitive?.intOrNull ?: rows.size
        if (skip > 0 && rows.size == limit && pageNumber * limit < total) {
            rows += native.post("item/list", parameters(libraryId, query, pageNumber + 1, limit)).jsonObject.rows()
        }
        return Page(rows.drop(skip).take(limit), offset, total)
    }

    suspend fun search(query: String): List<JsonObject> {
        val url = "http://fnos/search/list".toHttpUrl().newBuilder().addQueryParameter("q", query.trim()).build()
        return (native.get("search/list?${url.encodedQuery}") as? JsonArray).orEmpty().map { it.jsonObject }
    }

    suspend fun genres(libraryId: String): List<FilterOption> {
        val url = "http://fnos/tag/list".toHttpUrl().newBuilder().addQueryParameter("ancestor_guid", libraryId).build()
        val ids = (native.get("tag/list?${url.encodedQuery}").jsonObject["genres"] as? JsonArray)
            .orEmpty().map { it.jsonPrimitive.content }.toSet()
        return (native.get("tag/genres") as? JsonArray).orEmpty().mapNotNull {
            val genre = it.jsonObject
            val id = genre["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val title = genre["value"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (id in ids) FilterOption(id, title) else null
        }.sortedBy { it.title }
    }

    private fun parameters(libraryId: String, query: LibraryQuery, page: Int, size: Int): JsonObject = buildJsonObject {
        put("ancestor_guid", libraryId)
        put("page", page)
        put("page_size", size)
        put("tags", buildJsonObject {
            put("type", JsonArray(listOf(JsonPrimitive("Movie"), JsonPrimitive("TV"))))
            query.genreId?.let { put("genres", it.toIntOrNull() ?: error("Invalid fnOS genre ID")) }
            when (query.watch) {
                WatchFilter.ALL -> Unit
                WatchFilter.WATCHED -> put("watched", "1")
                WatchFilter.UNWATCHED -> put("watched", "0")
            }
            when (query.resolution) {
                ResolutionFilter.ALL -> Unit
                ResolutionFilter.UHD_4K -> put("resolution", "4k")
                ResolutionFilter.FHD_1080 -> put("resolution", "1080p")
                ResolutionFilter.HD_720 -> put("resolution", "720p")
                ResolutionFilter.SD -> error("SD requires compatibility filtering")
            }
        })
        put("sort_column", when (query.sort) {
            SortOrder.TITLE -> "sort_title"
            SortOrder.ADDED -> "create_time"
            SortOrder.RELEASED -> "release_date"
            SortOrder.RATING -> "vote_average"
            SortOrder.LAST_VIEWED -> error("Last viewed requires compatibility sorting")
        })
        put("sort_type", if (query.sort == SortOrder.TITLE) "ASC" else "DESC")
    }
}

internal fun JsonObject.fnOsMatch(catalog: List<BaseItem>): BaseItem? {
    val id = this["guid"]?.jsonPrimitive?.content ?: return null
    val item = catalog.firstOrNull { it.id == id || it.mediaSources.any { source -> source.id == id } } ?: return null
    val watched = this["watched"]?.jsonPrimitive?.intOrNull ?: return item
    return item.copy(userData = (item.userData ?: UserData()).copy(played = watched == 1))
}

private fun JsonObject.rows(): List<JsonObject> = (this["list"] as? JsonArray).orEmpty().map { it.jsonObject }

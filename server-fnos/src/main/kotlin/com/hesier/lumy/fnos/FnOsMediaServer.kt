package com.hesier.lumy.fnos

import com.hesier.lumy.core.FilterOption
import com.hesier.lumy.core.Hub
import com.hesier.lumy.core.HubLabel
import com.hesier.lumy.core.HubStyle
import com.hesier.lumy.core.Library
import com.hesier.lumy.core.LibraryQuery
import com.hesier.lumy.core.LetterBucket
import com.hesier.lumy.core.MediaDetail
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.Page
import com.hesier.lumy.core.PlaybackRequest
import com.hesier.lumy.core.PlaybackState
import com.hesier.lumy.core.ServerSession
import com.hesier.lumy.core.SortOrder
import com.hesier.lumy.core.ResolutionFilter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.io.IOException

/** fnOS exposes a partial Jellyfin API, with different browse and subtitle routes. */
class FnOsMediaServer(
    override val session: ServerSession,
    private val client: FnOsClient,
) : MediaServer {
    override val embedsSubtitles: Boolean get() = false
    private val catalogLock = Mutex()
    private val catalogs = mutableMapOf<String, Pair<Long, List<BaseItem>>>()
    private val knownSeasons = ConcurrentHashMap<String, BaseItem>()
    private val native = FnOsNativeClient(session, client)
    private val progress = FnOsProgress(native)
    private val queries = FnOsQueries(native)
    private val playback = FnOsPlayback(session, client, native)

    override suspend fun isReachable(): Boolean {
        val http = client.http.newBuilder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        return runCatching {
            client.fetch<PublicSystemInfo>(client.request("${session.baseUrl}/System/Info/Public", null).build(), http)
        }.getOrNull()?.id == session.serverId
    }

    override suspend fun letterIndex(libraryId: String, query: LibraryQuery): List<LetterBucket> = emptyList()

    // Native progress reports persist the selected tracks.
    override suspend fun saveStreamSelection(version: MediaVersion, audioId: String?, subtitleId: String?) = Unit

    override suspend fun playbackSource(request: PlaybackRequest) = playback.source(request)

    override suspend fun stopPlayback(request: PlaybackRequest) = playback.stop(request)

    override fun playbackHeaders(): Map<String, String> =
        client.headers(session.token, session.deviceId ?: client.info.deviceId) +
            ("Authorization" to session.token)

    override suspend fun libraries(): List<Library> = get<ItemsResult>("UserViews").items.map { view ->
        val first = get<ItemsResult>("Items", "parentId" to view.id, "limit" to "1").items.firstOrNull()
        Library(view.id, view.name, if (first?.type == "Series") MediaType.SHOW else MediaType.MOVIE)
    }

    override suspend fun homeHubs(): List<Hub> {
        val records = progress.resume()
        val resume = records.take(HUB_LIMIT).mapNotNull { record ->
            get<BaseItem?>("Items/${record.mediaId}")?.withProgress(records)
        }
        val latest = libraries().map { library ->
            Hub(
                id = "latest.${library.id}", title = library.title, style = HubStyle.POSTER,
                items = queryItems(library.id, 0, HUB_LIMIT, LibraryQuery(sort = SortOrder.ADDED)).items
                    .map { it.withProgress(records) },
                label = HubLabel.RecentlyAdded(library.title),
            )
        }
        return (listOf(Hub("resume", "Continue Watching", HubStyle.LANDSCAPE, resume, HubLabel.ContinueWatching)) + latest)
            .filter { it.items.isNotEmpty() }
    }

    override suspend fun libraryItems(libraryId: String, offset: Int, limit: Int, query: LibraryQuery): Page<MediaItem> {
        val page = queryItems(libraryId, offset, limit, query)
        val records = progress.resume()
        return Page(page.items.map { it.withProgress(records) }, offset, page.total)
    }

    override suspend fun genres(libraryId: String): List<FilterOption> =
        queries.genres(libraryId)

    override suspend fun setWatched(id: String, watched: Boolean) {
        val canonicalId = item(id).let { it.mediaSources.firstOrNull()?.id ?: it.id }
        val body = buildJsonObject { put("item_guid", canonicalId) }
        val accepted = if (watched) native.post("item/watched", body) else native.delete("item/watched", body)
        if (accepted != JsonPrimitive(true)) throw IOException("fnOS watched state was not accepted")
        catalogLock.withLock { catalogs.clear() }
    }

    override suspend fun reportProgress(request: PlaybackRequest, state: PlaybackState, positionMs: Long, durationMs: Long) {
        progress.report(request, state, positionMs, durationMs)
        catalogLock.withLock { catalogs.clear() }
    }

    override suspend fun search(query: String): List<Hub> {
        if (query.isBlank()) return emptyList()
        val records = progress.resume()
        val found = queries.search(query)
        return libraries().mapNotNull { library ->
            val rows = found.filter { it["ancestor_guid"]?.jsonPrimitive?.content == library.id }
            if (rows.isEmpty()) return@mapNotNull null
            val catalog = catalog(library.id)
            val matches = rows.mapNotNull { it.fnOsMatch(catalog) }
            matches.takeIf { it.isNotEmpty() }?.let {
                Hub(
                    "search.${library.id}", library.title, HubStyle.POSTER,
                    it.take(SEARCH_LIMIT).map { item -> item.withProgress(records) },
                    HubLabel.SearchResults(library.type, library.title),
                )
            }
        }
    }

    override suspend fun detail(id: String): MediaDetail {
        val item = item(id)
        val canonicalId = item.mediaSources.firstOrNull()?.id ?: item.id
        val state = native.get("item/$canonicalId").jsonObject
        val watched = state["is_watched"]?.jsonPrimitive?.intOrNull == 1
        val normalized = item.copy(userData = (item.userData ?: UserData()).copy(played = watched))
        val info = if (item.mediaSources.isNotEmpty()) native.post("play/info", buildJsonObject {
            put("item_guid", canonicalId)
        }).jsonObject else null
        val detail = normalized.fnOsDetail()
        val versions = detail.versions.map { version ->
            val streams = native.post("stream", buildJsonObject {
                put("media_guid", item.id)
                put("level", 1)
                put("ip", session.deviceId ?: client.info.deviceId)
                put("header", JsonObject(mapOf("User-Agent" to JsonArray(listOf(JsonPrimitive("Lumy"))))))
            }).jsonObject
            version.withFnOsStreams(streams).withFnOsStreamSelection(info)
        }
        val duration = versions.firstOrNull()?.part?.durationMs
        return detail.copy(item = normalized.withProgress(progress.resume()).copy(durationMs = duration ?: detail.item.durationMs),
            versions = versions)
    }

    override suspend fun children(id: String): List<MediaItem> {
        val parent = item(id)
        val children = when (parent.type) {
            "Series" -> seasons(id)
            "Season" -> episodes(parent.seriesId ?: parent.parentId ?: return emptyList(), id)
            else -> get<ItemsResult>("Items", "parentId" to id).items
        }
        val records = progress.resume()
        return children.map { it.withProgress(records) }
    }

    override suspend fun nextEpisode(episode: MediaItem): MediaItem? {
        val series = episode.grandparentId ?: return null
        val seasons = seasons(series).sortedBy { it.indexNumber ?: 0 }
        val index = seasons.indexOfFirst { it.id == episode.parentId }
        if (index < 0) return null
        val current = episodes(series, seasons[index].id)
        val next = current.indexOfFirst { it.id == episode.id }
        if (next < 0) return null
        current.getOrNull(next + 1)?.let { return it.withProgress(progress.resume()) }
        for (season in seasons.drop(index + 1)) {
            episodes(series, season.id).firstOrNull()?.let { return it.withProgress(progress.resume()) }
        }
        return null
    }

    // Keep optional detail features disabled until their native contracts are supported.
    override suspend fun collections(libraryId: String): List<MediaItem> = emptyList()
    override suspend fun playlists(): List<MediaItem> = emptyList()
    override suspend fun containerItems(container: MediaItem): List<MediaItem> = emptyList()
    override suspend fun similar(id: String): List<MediaItem> = emptyList()
    override suspend fun personItems(libraryId: String, personId: String): List<MediaItem> = emptyList()

    override fun imageUrl(ref: String, width: Int, height: Int): String =
        "${session.baseUrl}/${ref.replace("/Images/Backdrop/0", "/Images/Backdrop")}".toHttpUrl().newBuilder()
            .addQueryParameter("maxWidth", width.toString()).addQueryParameter("maxHeight", height.toString())
            .build().toString()

    private suspend fun item(id: String): BaseItem = knownSeasons[id]
        ?: get<BaseItem?>("Items/$id") ?: throw FnOsHttpException(404, "HTTP 404 Items/$id")

    private suspend fun seasons(seriesId: String): List<BaseItem> =
        get<ItemsResult>("Shows/$seriesId/Seasons").items.onEach { knownSeasons[it.id] = it }

    private suspend fun episodes(seriesId: String, seasonId: String): List<BaseItem> =
        get<ItemsResult>("Shows/$seriesId/Episodes", "seasonId" to seasonId).items.sortedBy { it.indexNumber ?: 0 }

    private suspend fun catalog(libraryId: String): List<BaseItem> = catalogLock.withLock {
        val cached = catalogs[libraryId]
        if (cached != null && System.nanoTime() - cached.first < CATALOG_TTL_NS) return@withLock cached.second
        val result = mutableListOf<BaseItem>()
        do {
            val page = get<ItemsResult>(
                "Items", "parentId" to libraryId, "recursive" to "true",
                "startIndex" to result.size.toString(), "limit" to PAGE_SIZE.toString(),
            )
            result.addAll(page.items)
        } while (page.items.isNotEmpty() && result.size < (page.totalRecordCount ?: result.size))
        result.distinctBy { it.id }.filter { it.type == "Movie" || it.type == "Series" }
            .also { catalogs[libraryId] = System.nanoTime() to it }
    }

    private suspend fun queryItems(libraryId: String, offset: Int, limit: Int, query: LibraryQuery): Page<BaseItem> {
        require(offset >= 0 && limit > 0)
        val catalog = catalog(libraryId)
        if (query.sort != SortOrder.LAST_VIEWED && query.resolution != ResolutionFilter.SD) {
            val page = queries.page(libraryId, offset, limit, query)
            return Page(page.items.mapNotNull { it.fnOsMatch(catalog) }, offset, page.total)
        }
        // Native sorting ignores watched_ts; native SD excludes other low resolutions.
        val nativeQuery = query.copy(sort = SortOrder.TITLE, resolution = ResolutionFilter.ALL)
        val matches = mutableListOf<BaseItem>()
        var loaded = 0
        do {
            val page = queries.page(libraryId, loaded, PAGE_SIZE, nativeQuery)
            matches += page.items.mapNotNull { it.fnOsMatch(catalog) }
            loaded += page.items.size
        } while (page.items.isNotEmpty() && loaded < (page.total ?: loaded))
        val sorted = matches.fnOsQuery(query.copy(genreId = null))
        return Page(sorted.drop(offset).take(limit), offset, sorted.size)
    }

    private fun BaseItem.withProgress(records: List<FnOsResume>): MediaItem {
        val record = records.firstOrNull { it.mediaId == id }
        val item = fnOsItem().toItem()
        return item.copy(viewOffsetMs = record?.positionMs, durationMs = item.durationMs ?: record?.durationMs)
    }

    private suspend inline fun <reified T> get(path: String, vararg params: Pair<String, String>): T {
        val url = "${session.baseUrl}/$path".toHttpUrl().newBuilder()
            .addQueryParameter("userId", session.userId)
        params.forEach { (key, value) -> url.addQueryParameter(key, value) }
        return client.fetch(client.request(url.build().toString(), session.token,
            session.deviceId ?: client.info.deviceId).build())
    }

    private companion object {
        const val PAGE_SIZE = 200
        const val HUB_LIMIT = 20
        const val SEARCH_LIMIT = 60
        const val CATALOG_TTL_NS = 30_000_000_000L
    }
}

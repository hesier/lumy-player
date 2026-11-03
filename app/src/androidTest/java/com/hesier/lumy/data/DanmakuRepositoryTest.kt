package com.hesier.lumy.data

import android.content.Context
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.ServerDanmaku
import com.hesier.lumy.core.ServerDanmakuFormat
import com.hesier.lumy.net.danmaku.*
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue
import org.json.JSONArray
import org.json.JSONObject

/** Only an owned loopback fixture is used; user media and saved settings are untouched. */
internal class DanmakuRepositoryTest(private val context: Context) {
    private val preferences = context.getSharedPreferences("danmaku-repository-test", Context.MODE_PRIVATE)
    private val settings = DanmakuSourceSettings(preferences)
    private val store = DanmakuStore(context, "danmaku-repository-test")
    private val paths = CopyOnWriteArrayList<String>()
    private var pluginStatus = 200
    private var searchStatus = 200
    private var pluginBody = XML
    private var stale = false
    private var reusedId = false
    private var remake = false
    private var wrongDetails = false
    private var commentsBody = COMMENTS
    private var detailsOverride: String? = null
    private val queuedSearch = ConcurrentLinkedQueue<String>()

    fun runAll(): String = runBlocking {
        preferences.edit().clear().commit()
        store.clearCache(); store.clearMatches()
        val socket = ServerSocket(0)
        val server = Thread {
            while (!socket.isClosed) runCatching { socket.accept().use(::reply) }
        }.apply { isDaemon = true; start() }
        try {
            settings.customUrl = "http://127.0.0.1:${socket.localPort}/proxy/token"
            settings.onlineSource = DanmakuOnlineSource.CUSTOM
            val services = DanmakuServices(context, settings)
            val repository = DanmakuRepository(services, store)
            val media = media(socket.localPort)
            pluginPriority(repository, media)
            savedMatchOverridesPlugin(repository, media, services)
            manualAndCache(repository, media, services)
            identityAndRecovery(repository, media, services)
            automaticValidation(repository, media)
            automaticEpisodes(repository, media)
            automaticMoviesAndFallback(repository, media)
            restoreAutomatic(repository, media, services)
            keywordSequence(repository, media)
            "Plugin priority/fallback, manual pinning, stale IDs, cache and service/media isolation passed"
        } finally {
            socket.close(); server.join(2_000)
            store.clearCache(); store.clearMatches(); preferences.edit().clear().commit()
        }
    }

    private suspend fun savedMatchOverridesPlugin(repository: DanmakuRepository, media: DanmakuMedia, services: DanmakuServices) {
        val source = DanmakuChoice(DanmakuSourceKind.CUSTOM, services.endpoint.identity)
        val anime = repository.details(source, repository.search(source, "show").first())
        store.remember(media.key, DanmakuMatch(source, anime, anime.episodes.first(), 2023, 1, 1))
        store.clearCache(); paths.clear()
        val reopened = DanmakuStore(context, "danmaku-repository-test")
        val replay = DanmakuRepository(services, reopened)
        val loaded = replay.load(media) as DanmakuLoad.Loaded
        check(loaded.source == source && !loaded.automatic && loaded.comments.single().text == "online")
        check(paths.none { it.startsWith("/plugin") })
        check(reopened.match(media.key, source.identity)?.episode?.id == anime.episodes.first().id)
        paths.clear()
        check((replay.load(media, force = true) as DanmakuLoad.Loaded).source == source)
        check(paths.none { it.startsWith("/plugin") } && paths.any { "/comment/" in it })
        stale = true
        check(replay.load(media, force = true) is DanmakuLoad.NeedsMatch)
        check(reopened.match(media.key, source.identity) != null); stale = false
        reopened.restoreAuto(media.key); paths.clear()
        check((replay.load(media, force = true) as DanmakuLoad.Loaded).source.kind == DanmakuSourceKind.SERVER)
        check(paths.size == 1 && paths.single().startsWith("/plugin"))
        store.clearCache(); paths.clear()
    }
    private suspend fun pluginPriority(repository: DanmakuRepository, media: DanmakuMedia) {
        val plugin = repository.load(media) as DanmakuLoad.Loaded
        check(plugin.match == null && !plugin.automatic)
        check(paths.none { "/api/v2/" in it })
        store.clearCache(); paths.clear(); pluginBody = "<i/>"
        store.choose(media.key, DanmakuChoice(DanmakuSourceKind.SERVER, "server:${media.serverKey}"))
        val fallback = repository.load(media) as DanmakuLoad.Loaded
        check(fallback.automatic && fallback.match?.anime?.id == "1")
        check(paths.any { "/search/anime" in it })
        for (status in listOf(401, 403, 404)) {
            paths.clear(); pluginStatus = status
            check((repository.load(media) as DanmakuLoad.Loaded).automatic)
            check((if (status == 404) DanmakuFailure.NOT_FOUND else DanmakuFailure.AUTH) == repository.serverFailure)
            check(paths.size == 1 && paths.single().startsWith("/plugin"))
        }
        pluginStatus = 200; pluginBody = XML
        store.restoreAuto(media.key)
    }

    private suspend fun manualAndCache(repository: DanmakuRepository, media: DanmakuMedia, services: DanmakuServices) {
        val source = DanmakuChoice(DanmakuSourceKind.CUSTOM, services.endpoint.identity)
        val anime = repository.details(source, repository.search(source, "show").first())
        store.remember(media.key, DanmakuMatch(source, anime, anime.episodes.first(), 2023, 1, 1))
        paths.clear()
        val onlineMedia = media.copy(plugin = null)
        val loaded = repository.load(onlineMedia) as DanmakuLoad.Loaded
        check("online" == loaded.comments.single().text)
        check(!loaded.automatic && loaded.match?.anime?.id == anime.id)
        check(paths.none { it.startsWith("/plugin") })
        check(paths.all { it.startsWith("/proxy/token/api/v2/") })
        paths.clear(); check(repository.load(onlineMedia) is DanmakuLoad.Loaded)
        check(paths.none { "/comment/" in it })
        paths.clear()
        check(!(repository.load(onlineMedia, force = true) as DanmakuLoad.Loaded).automatic)
        check(paths.count { "/comment/" in it } == 1)
        store.clearCache()
        check(store.match(media.key, source.identity) != null)
        check(repository.load(onlineMedia) is DanmakuLoad.Loaded)
        paths.clear(); stale = true
        check(repository.load(onlineMedia, force = true) is DanmakuLoad.NeedsMatch)
        check(paths.none { it.startsWith("/plugin") })
        check(store.match(media.key, source.identity) != null)
        stale = false
    }

    private suspend fun identityAndRecovery(repository: DanmakuRepository, media: DanmakuMedia, services: DanmakuServices) {
        val onlineMedia = media.copy(plugin = null)
        val first = services.endpoint.identity
        settings.customUrl += "/other"
        val other = services.endpoint.identity
        check(first != other && store.match(media.key, other) == null)
        paths.clear()
        val switched = repository.load(media.copy(plugin = null)) as DanmakuLoad.Loaded
        check(switched.automatic && switched.source.identity == other)
        check(paths.all { it.startsWith("/proxy/token/other/api/v2/") })
        check(store.match(media.key, first) != null && store.match(media.key, other) == null)
        store.choose(media.key, DanmakuChoice(DanmakuSourceKind.CUSTOM, other))
        settings.customUrl = settings.customUrl.removeSuffix("/other")
        val restored = repository.load(onlineMedia) as DanmakuLoad.Loaded
        check(!restored.automatic && restored.source.identity == first)
        reusedId = true
        check(repository.load(onlineMedia, force = true) is DanmakuLoad.NeedsMatch)
        reusedId = false
        remake = true
        check(repository.load(onlineMedia, force = true) is DanmakuLoad.NeedsMatch)
        remake = false
        // Reusing an ID for a newly selected work must not hit the previous work's cache.
        check(repository.load(onlineMedia) is DanmakuLoad.Loaded)
        val old = checkNotNull(store.match(media.key, first))
        reusedId = true
        store.remember(media.key, old.copy(anime = old.anime.copy(title = "Other")))
        commentsBody = COMMENTS.replace("online", "newonline")
        val changed = repository.load(onlineMedia) as DanmakuLoad.Loaded
        check("newonline" == changed.comments.single().text)
        reusedId = false; commentsBody = COMMENTS
        store.restoreAuto(media.key)
        paths.clear(); check(repository.load(media) is DanmakuLoad.Loaded)
        check(paths.none { "/api/v2/" in it })
        paths.clear(); check(repository.load(media.copy(key = "different-episode")) is DanmakuLoad.Loaded)
        check(paths.any { it.startsWith("/plugin") })
        store.clearMatches()
        check(store.choice(media.key) == null && store.match(media.key, first) == null)
    }

    private suspend fun automaticValidation(repository: DanmakuRepository, media: DanmakuMedia) {
        val automatic = media.copy(key = "auto-first", plugin = null, year = null, season = 1)
        val loaded = repository.load(automatic) as DanmakuLoad.Loaded
        check(loaded.automatic && loaded.match?.anime?.id == "1")
        check(store.choice(automatic.key) == null)
        paths.clear()
        check((repository.load(automatic) as DanmakuLoad.Loaded).automatic)
        check(paths.none { "/comment/" in it })
        paths.clear()
        check((repository.load(automatic, force = true) as DanmakuLoad.Loaded).automatic)
        check(paths.count { "/comment/" in it } == 1)
        wrongDetails = true
        check(repository.load(automatic, force = true) is DanmakuLoad.NeedsMatch)
        wrongDetails = false
        detailsOverride = DETAILS.replace("\"animeId\":1", "\"animeId\":99")
        paths.clear()
        check((repository.load(automatic, force = true) as DanmakuLoad.Loaded).match?.anime?.id == "99")
        detailsOverride = null
    }

    private suspend fun automaticEpisodes(repository: DanmakuRepository, media: DanmakuMedia) {
        val current = media.copy(key = "auto-current", plugin = null, number = 2, season = 7)
        check(repository.load(current) is DanmakuLoad.NeedsMatch)
        detailsOverride = detailsWithEpisodes(DanmakuEpisode("22", "Current", "2", "opaque-season")).replace("Chosen", "Chosen Season 7")
        // JSON numeric episode numbers must work as well as their string representation.
        val root = JSONObject(detailsOverride!!)
        root.getJSONObject("bangumi").getJSONArray("episodes").getJSONObject(0).put("episodeNumber", 2)
        detailsOverride = root.toString()
        val loaded = repository.load(current) as DanmakuLoad.Loaded
        check(loaded.automatic && loaded.match?.episode?.id == "22" && loaded.match?.episode?.number == "2")
        detailsOverride = null
    }

    private suspend fun automaticMoviesAndFallback(repository: DanmakuRepository, media: DanmakuMedia) {
        val movie = media.copy(key = "auto-movie", type = MediaType.MOVIE, plugin = null)
        val trailer = DanmakuEpisode("10", "Trailer", "0")
        val feature = DanmakuEpisode("11", "Feature", "1", "opaque")
        detailsOverride = detailsWithEpisodes(trailer, feature).replace("\"year\":2023", "\"year\":2023,\"type\":\"Movie\"")
        check((repository.load(movie) as DanmakuLoad.Loaded).match?.episode?.id == "11")
        detailsOverride = detailsWithEpisodes(trailer)
        check(repository.load(movie, force = true) is DanmakuLoad.NeedsMatch)
        detailsOverride = DETAILS.replace("\"year\":2023", "\"year\":2023,\"type\":\"Movie\"")
        queuedSearch.add(SEARCH_EMPTY); queuedSearch.add(SEARCH)
        paths.clear()
        val original = repository.load(movie.copy(key = "original-fallback", title = "Unknown", originalTitle = "Chosen")) as DanmakuLoad.Loaded
        check(original.automatic && original.match?.anime?.id == "1")
        check(paths.count { "/search/anime" in it } == 2)
        queuedSearch.add(SEARCH_EMPTY)
        check(repository.load(movie, force = true) is DanmakuLoad.NeedsMatch)
        detailsOverride = null
    }

    private suspend fun restoreAutomatic(repository: DanmakuRepository, media: DanmakuMedia, services: DanmakuServices) {
        val movie = media.copy(key = "restore-first", plugin = null, type = MediaType.MOVIE)
        val source = DanmakuChoice(DanmakuSourceKind.CUSTOM, services.endpoint.identity)
        detailsOverride = DETAILS.replace("\"animeId\":1", "\"animeId\":2").replace("Chosen", "Other")
        val manual = repository.details(source, DanmakuAnime("2", "Other"))
        store.remember(movie.key, DanmakuMatch(source, manual, manual.episodes.first(), 2023, 1, 1))
        paths.clear()
        val pinned = repository.load(movie) as DanmakuLoad.Loaded
        check(!pinned.automatic && pinned.match?.anime?.id == "2")
        check(paths.none { "/search/anime" in it })
        store.restoreAuto(movie.key)
        detailsOverride = DETAILS.replace("\"year\":2023", "\"year\":2023,\"type\":\"Movie\"")
        paths.clear()
        val automatic = repository.load(movie) as DanmakuLoad.Loaded
        check(automatic.automatic && automatic.match?.anime?.id == "1")
        check(paths.any { "/search/anime" in it })
        detailsOverride = null
    }
    private suspend fun keywordSequence(repository: DanmakuRepository, media: DanmakuMedia) {
        val current = media.copy(key = "search-sequence", title = "三体", plugin = null)
        queuedSearch.add(SEARCH_EMPTY); queuedSearch.add(SEARCH)
        paths.clear()
        check(repository.load(current) is DanmakuLoad.NeedsMatch)
        var searches = paths.filter { "/search/anime" in it }
        fun keyword(path: String) = java.net.URLDecoder.decode(path.substringAfter("keyword="), "UTF-8")
        check(listOf("三体 S01E01", "三体") == searches.map(::keyword))
        check(searches.first().contains("%E4%B8%89%E4%BD%93%20S01E01"))
        repeat(3) { queuedSearch.add(SEARCH_EMPTY) }; queuedSearch.add(SEARCH)
        paths.clear()
        check(repository.load(current.copy(originalTitle = "Chosen")) is DanmakuLoad.Loaded)
        searches = paths.filter { "/search/anime" in it }
        check(listOf("三体 S01E01", "三体", "Chosen S01E01", "Chosen") == searches.map(::keyword))
        searchStatus = 500
        paths.clear()
        val failed = repository.load(current) as DanmakuLoad.Failed
        check(failed.reason == DanmakuFailure.UNAVAILABLE && !failed.manual)
        check(paths.count { "/search/anime" in it } == 1)
        searchStatus = 200
    }

    private fun detailsWithEpisodes(vararg episodes: DanmakuEpisode): String {
        val root = JSONObject(DETAILS)
        val rows = JSONArray()
        episodes.forEach { item -> rows.put(JSONObject().put("episodeId", item.id).put("episodeTitle", item.title)
            .put("episodeNumber", item.number).put("seasonId", item.seasonId)) }
        root.getJSONObject("bangumi").put("episodes", rows)
        return root.toString()
    }

    private fun reply(socket: Socket) {
        val input = socket.getInputStream().bufferedReader()
        val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return
        while (!input.readLine().isNullOrEmpty()) Unit
        paths += path
        val (status, body) = when {
            path.startsWith("/plugin") -> pluginStatus to pluginBody
            "/search/anime" in path -> searchStatus to (queuedSearch.poll() ?: if (stale || reusedId) SEARCH_EMPTY else if (remake) SEARCH.replace("2023", "2024") else SEARCH)
            "/bangumi/" in path -> if (stale) 404 to "{}" else 200 to (detailsOverride ?: if (reusedId || wrongDetails) DETAILS.replace("Chosen", "Other") else if (remake) DETAILS.replace("2023", "2024") else DETAILS)
            "/comment/" in path -> 200 to commentsBody
            else -> 404 to "{}"
        }
        val bytes = body.toByteArray()
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status Result\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
    }

    private fun media(port: Int) = DanmakuMedia("item", "server", "Chosen", null,
        MediaType.EPISODE, 2023, 1, 1, ServerDanmaku("http://127.0.0.1:$port/plugin",
            mapOf("X-Emby-Token" to "media-only"), ServerDanmakuFormat.XML))

    companion object {
        const val XML = "<i><d p=\"1.0,1,25,16777215,0,0,user,1\">plugin</d></i>"
        const val SEARCH_EMPTY = "{\"success\":true,\"animes\":[]}"
        const val SEARCH = "{\"success\":true,\"animes\":[{\"animeId\":1,\"animeTitle\":\"Chosen\",\"year\":2023},{\"animeId\":2,\"animeTitle\":\"Other\"}]}"
        const val DETAILS = "{\"success\":true,\"bangumi\":{\"animeId\":1,\"animeTitle\":\"Chosen\",\"year\":2023,\"episodes\":[{\"episodeId\":11,\"episodeTitle\":\"First\",\"episodeNumber\":\"1\"}]}}"
        const val COMMENTS = "{\"comments\":[{\"cid\":1,\"p\":\"1,1,16777215,user\",\"m\":\"online\"}]}"
    }
}

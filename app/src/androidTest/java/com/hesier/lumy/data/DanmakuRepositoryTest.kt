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

/** Only an owned loopback fixture is used; user media and saved settings are untouched. */
internal class DanmakuRepositoryTest(private val context: Context) {
    private val preferences = context.getSharedPreferences("danmaku-repository-test", Context.MODE_PRIVATE)
    private val settings = DanmakuSourceSettings(preferences)
    private val store = DanmakuStore(context, "danmaku-repository-test")
    private val paths = CopyOnWriteArrayList<String>()
    private var pluginStatus = 200
    private var pluginBody = XML
    private var stale = false
    private var reusedId = false
    private var remake = false
    private var unique = false
    private var wrongDetails = false
    private var commentsBody = COMMENTS

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
            manualAndCache(repository, media, services)
            identityAndRecovery(repository, media, services)
            automaticValidation(repository, media)
            "Plugin priority/fallback, manual pinning, stale IDs, cache and service/media isolation passed"
        } finally {
            socket.close(); server.join(2_000)
            store.clearCache(); store.clearMatches(); preferences.edit().clear().commit()
        }
    }

    private suspend fun pluginPriority(repository: DanmakuRepository, media: DanmakuMedia) {
        check(repository.load(media) is DanmakuLoad.Loaded)
        check(paths.none { "/api/v2/" in it })
        store.clearCache(); paths.clear(); pluginBody = "<i/>"
        check(repository.load(media) is DanmakuLoad.NeedsMatch)
        check(paths.any { "/search/anime" in it })
        paths.clear(); pluginStatus = 401
        check(repository.load(media) is DanmakuLoad.NeedsMatch)
        check(DanmakuFailure.AUTH == repository.serverFailure)
        check(paths.any { "/search/anime" in it })
        pluginStatus = 200; pluginBody = XML
    }

    private suspend fun manualAndCache(repository: DanmakuRepository, media: DanmakuMedia, services: DanmakuServices) {
        val source = DanmakuChoice(DanmakuSourceKind.CUSTOM, services.endpoint.identity)
        val anime = repository.details(source, repository.search(source, "show").first())
        store.remember(media.key, DanmakuMatch(source, anime, anime.episodes.first(), 2023, 1, 1))
        paths.clear()
        val loaded = repository.load(media) as DanmakuLoad.Loaded
        check("online" == loaded.comments.single().text)
        check(paths.none { it.startsWith("/plugin") })
        check(paths.all { it.startsWith("/proxy/token/api/v2/") })
        paths.clear(); check(repository.load(media) is DanmakuLoad.Loaded)
        check(paths.none { "/comment/" in it })
        store.clearCache()
        check(store.match(media.key, source.identity) != null)
        check(repository.load(media) is DanmakuLoad.Loaded)
        paths.clear(); stale = true
        check(repository.load(media, force = true) is DanmakuLoad.NeedsMatch)
        check(paths.none { it.startsWith("/plugin") })
        check(store.match(media.key, source.identity) != null)
        stale = false
    }

    private suspend fun identityAndRecovery(repository: DanmakuRepository, media: DanmakuMedia, services: DanmakuServices) {
        val first = services.endpoint.identity
        settings.customUrl += "/other"
        val other = services.endpoint.identity
        check(first != other && store.match(media.key, other) == null)
        val failure = repository.load(media) as DanmakuLoad.Failed
        check(failure.manual && failure.reason == DanmakuFailure.UNAVAILABLE)
        settings.customUrl = settings.customUrl.removeSuffix("/other")
        reusedId = true
        check(repository.load(media, force = true) is DanmakuLoad.NeedsMatch)
        reusedId = false
        remake = true
        check(repository.load(media, force = true) is DanmakuLoad.NeedsMatch)
        remake = false
        // Reusing an ID for a newly selected work must not hit the previous work's cache.
        check(repository.load(media) is DanmakuLoad.Loaded)
        val old = checkNotNull(store.match(media.key, first))
        reusedId = true
        store.remember(media.key, old.copy(anime = old.anime.copy(title = "Other")))
        commentsBody = COMMENTS.replace("online", "newonline")
        val changed = repository.load(media) as DanmakuLoad.Loaded
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
        val movie = media.copy(key = "auto-movie", title = "Chosen", type = MediaType.MOVIE, plugin = null)
        unique = true
        val loaded = repository.load(movie) as DanmakuLoad.Loaded
        check(loaded.comments.isNotEmpty())
        wrongDetails = true
        check(repository.load(movie, force = true) is DanmakuLoad.NeedsMatch)
        unique = false; wrongDetails = false
    }

    private fun reply(socket: Socket) {
        val input = socket.getInputStream().bufferedReader()
        val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return
        while (!input.readLine().isNullOrEmpty()) Unit
        paths += path
        val (status, body) = when {
            path.startsWith("/plugin") -> pluginStatus to pluginBody
            "/search/anime" in path -> 200 to if (stale || reusedId) SEARCH_EMPTY else if (remake) SEARCH.replace("2023", "2024") else if (unique) UNIQUE else SEARCH
            "/bangumi/" in path -> if (stale) 404 to "{}" else 200 to if (reusedId || wrongDetails) DETAILS.replace("Chosen", "Other") else if (remake) DETAILS.replace("2023", "2024") else if (unique) DETAILS.replace("\"year\":2023", "\"year\":2023,\"typeDescription\":\"Movie\"") else DETAILS
            "/comment/" in path -> 200 to commentsBody
            else -> 404 to "{}"
        }
        val bytes = body.toByteArray()
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status Result\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
    }

    private fun media(port: Int) = DanmakuMedia("item", "server", "Uncertain", null,
        MediaType.EPISODE, 2023, 1, 1, ServerDanmaku("http://127.0.0.1:$port/plugin",
            mapOf("X-Emby-Token" to "media-only"), ServerDanmakuFormat.XML))

    companion object {
        const val XML = "<i><d p=\"1.0,1,25,16777215,0,0,user,1\">plugin</d></i>"
        const val SEARCH_EMPTY = "{\"success\":true,\"animes\":[]}"
        const val SEARCH = "{\"success\":true,\"animes\":[{\"animeId\":1,\"animeTitle\":\"Chosen\",\"year\":2023},{\"animeId\":2,\"animeTitle\":\"Other\"}]}"
        const val UNIQUE = "{\"success\":true,\"animes\":[{\"animeId\":1,\"animeTitle\":\"Chosen\",\"year\":2023,\"typeDescription\":\"Movie\"}]}"
        const val DETAILS = "{\"success\":true,\"bangumi\":{\"animeId\":1,\"animeTitle\":\"Chosen\",\"year\":2023,\"episodes\":[{\"episodeId\":11,\"episodeTitle\":\"First\",\"episodeNumber\":\"1\"}]}}"
        const val COMMENTS = "{\"comments\":[{\"cid\":1,\"p\":\"1,1,16777215,user\",\"m\":\"online\"}]}"
    }
}

package com.hesier.lumy.data

import android.content.Context
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.ServerDanmaku
import com.hesier.lumy.core.ServerDanmakuFormat
import com.hesier.lumy.net.danmaku.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Offline oversized bodies exercise the real transport limit without allocating oversized fixtures. */
internal class DanmakuOversizeTest(private val context: Context) {
    private val media = DanmakuMedia("oversize-fixture", "server", "Story", "Original Story",
        MediaType.EPISODE, 2023, 1, 2, null)

    fun runAll(): String = runBlocking {
        val cases: List<Pair<String, suspend () -> Unit>> = listOf(
            "match" to { matchFallback() }, "search" to { searchFallback() },
            "details" to { detailsFallback() }, "all routes" to { allOversized() },
            "comments" to { commentsStayBounded() }, "saved binding" to { savedBinding() },
            "source cache" to { sourceCachePriority() }, "cancellation" to { cancellation() })
        for ((name, action) in cases) try { withTimeout(5_000) { action() } }
        catch (error: Exception) { throw AssertionError("Oversized danmaku case: $name", error) }
        "Oversized match/search/details preserve complete suggestions without auto-selection; " +
            "comments stay bounded; saved bindings, service cache isolation, request budgets and cancellation passed"
    }

    private suspend fun matchFallback() = Fixture().use { f ->
        f.oversized += "match"
        pending(DanmakuAutoMatch(f.api).run(media), "b")
        check(f.routes.count { it == "match" } == 1)
        check(f.routes.count { it == "details:b" } == 1)
        check(f.routes.none { it.startsWith("comment") })
    }

    private suspend fun searchFallback() = Fixture().use { f ->
        f.oversizeFirstSearch = true
        pending(DanmakuAutoMatch(f.api).run(media), "b")
        check(f.routes.count { it == "search" } == 4)
        check(f.searchKeywords.distinct().size == 4)
        check(f.routes.count { it == "details:b" } == 1)
    }

    private suspend fun detailsFallback() = Fixture().use { f ->
        f.rows = listOf("a", "b"); f.matchIds = f.rows
        f.oversized += "details:a"
        val decision = DanmakuAutoMatch(f.api).run(media)
        pending(decision, "b")
        check(decision.suggestions.none { it.anime.id == "a" })
        // A failed match detail is memoized, not retried for every keyword/candidate.
        check(f.routes.count { it == "details:a" } == 1 && f.routes.count { it == "details:b" } == 1)
    }

    private suspend fun allOversized() = Fixture().use { f ->
        f.oversized += listOf("match", "search")
        val decision = DanmakuAutoMatch(f.api).run(media)
        check(decision.selected == null && decision.reason == DanmakuMatchReason.INCOMPLETE)
        check(decision.suggestions.isEmpty())
        check(f.routes.size == 5 && f.routes.count { it == "search" } == 4)
    }

    private suspend fun commentsStayBounded() = repositoryFixture { f, repository, store, _ ->
        f.matchIds = listOf("b"); f.oversized += "comment:b2"
        repeat(2) {
            val result = repository.load(media, force = true)
            check(result is DanmakuLoad.Failed && result.reason == DanmakuFailure.TOO_LARGE)
            check(store.cachedAutomatic(media, f.endpoint.identity) == null)
        }
        check(f.routes.count { it == "comment:b2" } == 2)
        f.oversized.clear()
        val loaded = repository.load(media) as DanmakuLoad.Loaded
        check(loaded.comments.single().text == "complete")
        f.routes.clear()
        check((repository.load(media) as DanmakuLoad.Loaded).comments == loaded.comments)
        check(f.routes.isEmpty())
    }

    private suspend fun savedBinding() = repositoryFixture { f, repository, store, _ ->
        val saved = saved(f)
        store.remember(media.key, saved)
        f.oversized += "details:b"
        val result = repository.load(media, force = true)
        check(result is DanmakuLoad.NeedsMatch && result.reason == DanmakuMatchReason.INCOMPLETE)
        check(store.match(media.key, saved.choice.identity)?.episode == saved.episode)
        check(f.routes == listOf("details:b"))
    }

    private suspend fun sourceCachePriority() = repositoryFixture { f, repository, store, settings ->
        val saved = saved(f)
        store.remember(media.key, saved)
        store.cache(store.contentKey(saved), listOf(DanmakuComment(1, 0, "cached", 1, 0xffffff)))
        val plugin = media.copy(plugin = ServerDanmaku("http://127.0.0.1/plugin", emptyMap(), ServerDanmakuFormat.XML))
        val cached = repository.load(plugin) as DanmakuLoad.Loaded
        check(cached.source == saved.choice && !cached.automatic && cached.comments.single().text == "cached")
        check(f.routes.isEmpty())
        // A different configured service must not reuse either this manual match or its cached content.
        settings.customUrl = f.endpoint.baseUrl + "/another-service"
        f.matchIds = listOf("b")
        val switched = repository.load(media) as DanmakuLoad.Loaded
        check(switched.source.identity != saved.choice.identity && switched.automatic)
        check(switched.comments.single().text == "complete" && f.routes.any { it == "comment:b2" })
        settings.customUrl = f.endpoint.baseUrl; f.routes.clear()
        check(!(repository.load(plugin) as DanmakuLoad.Loaded).automatic && f.routes.isEmpty())
    }

    private suspend fun cancellation() = Fixture().use { f ->
        f.blockMatch = true
        coroutineScope {
            val request = launch { DanmakuAutoMatch(f.api).run(media) }
            check(withContext(Dispatchers.IO) { f.matchStarted.await(2, TimeUnit.SECONDS) })
            request.cancelAndJoin()
            f.releaseMatch.countDown()
            check(request.isCancelled && f.routes == listOf("match"))
        }
    }

    private fun pending(decision: DanmakuDecision, id: String) {
        check(decision.selected == null && decision.reason == DanmakuMatchReason.INCOMPLETE)
        check(decision.suggestions.any { it.anime.id == id && it.episode?.id == "${id}2" && it.anime.episodes.isNotEmpty() })
    }

    private fun saved(f: Fixture): DanmakuMatch {
        val episode = DanmakuEpisode("b2", "Episode 2", "2", "season")
        val anime = DanmakuAnime("b", "Story", "TV", 2023, listOf(episode))
        return DanmakuMatch(DanmakuChoice(DanmakuSourceKind.CUSTOM, f.endpoint.identity), anime, episode, 2023, 1, 2)
    }

    private suspend fun repositoryFixture(action: suspend (Fixture, DanmakuRepository, DanmakuStore, DanmakuSourceSettings) -> Unit) {
        val name = "danmaku-oversize-test"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val store = DanmakuStore(context, name)
        check(preferences.edit().clear().commit()); store.clearCache(); store.clearMatches()
        try {
            Fixture().use { f ->
                val settings = DanmakuSourceSettings(preferences).apply {
                    onlineSource = DanmakuOnlineSource.CUSTOM; customUrl = f.endpoint.baseUrl
                }
                action(f, DanmakuRepository(DanmakuServices(context, settings), store, f.http), store, settings)
            }
        } finally { store.clearCache(); store.clearMatches(); check(preferences.edit().clear().commit()) }
    }

    private class Fixture : AutoCloseable {
        val routes = CopyOnWriteArrayList<String>()
        val searchKeywords = CopyOnWriteArrayList<String>()
        val oversized = mutableSetOf<String>()
        var rows = listOf("b")
        var matchIds: List<String>? = null
        var oversizeFirstSearch = false
        @Volatile var blockMatch = false
        val matchStarted = CountDownLatch(1)
        val releaseMatch = CountDownLatch(1)
        val endpoint = DanmakuEndpoint("http://127.0.0.1:12872/fixture")
        val http = OkHttpClient.Builder().addInterceptor(DanmakuDiagnostics).addInterceptor { chain ->
            val request = chain.request()
            val parts = request.url.pathSegments
            val route = when {
                parts.last() == "match" -> "match"
                "search" in parts -> "search"
                "bangumi" in parts -> "details:${parts.last()}"
                "comment" in parts -> "comment:${parts.last()}"
                else -> "other"
            }
            routes += route
            if (route == "search") searchKeywords += request.url.queryParameter("keyword").orEmpty()
            if (route == "match") {
                matchStarted.countDown()
                if (blockMatch) check(releaseMatch.await(3, TimeUnit.SECONDS))
            }
            val tooLarge = route in oversized || (route == "search" && oversizeFirstSearch && searchKeywords.size == 1)
            val code = if (route == "match" && matchIds == null && !tooLarge) 404 else 200
            val body = if (tooLarge) oversizedBody() else body(route).toResponseBody()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body).build()
        }.build()
        val api = DanmakuApi(http, endpoint)

        private fun body(route: String): String = when {
            route == "match" -> JSONObject().put("isMatched", true).put("matches", JSONArray(matchIds.orEmpty().map {
                JSONObject().put("animeId", it).put("episodeId", "${it}2")
            })).toString()
            route == "search" -> JSONObject().put("animes", JSONArray(rows.map { anime(it, false) })).toString()
            route.startsWith("details:") -> JSONObject().put("bangumi", anime(route.substringAfter(':'), true)).toString()
            else -> """{"comments":[{"cid":1,"p":"1,1,16777215,fixture","m":"complete"}]}"""
        }

        private fun anime(id: String, episodes: Boolean): JSONObject {
            val value = JSONObject().put("animeId", id).put("animeTitle", "Story").put("type", "TV").put("year", 2023)
            if (episodes) value.put("episodes", JSONArray().put(JSONObject().put("episodeId", "${id}2")
                .put("episodeTitle", "Episode 2").put("episodeNumber", "2").put("seasonId", "season")))
            return value
        }

        private fun oversizedBody() = object : ResponseBody() {
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength() = 8L * 1024 * 1024 + 1
            override fun source() = Buffer().writeUtf8("{}")
        }

        override fun close() {
            releaseMatch.countDown()
            http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
            check(http.dispatcher.executorService.awaitTermination(3, TimeUnit.SECONDS))
        }
    }
}

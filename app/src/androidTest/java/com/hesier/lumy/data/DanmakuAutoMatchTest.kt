package com.hesier.lumy.data

import android.content.Context
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.net.danmaku.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** Deterministic API responses exercise orchestration, not only the scoring functions. */
internal class DanmakuAutoMatchTest(private val context: Context) {
    private val media = DanmakuMedia("auto-fixture", "server", "Story", null, MediaType.EPISODE, 2023, 1, 2, null)

    fun runAll(): String = runBlocking {
        val cases: List<Pair<String, suspend () -> Unit>> = listOf(
            "confirmed" to { confirmedMapping() }, "stale" to { staleAndSuggested() },
            "ambiguous" to { ambiguousAndTruncated() }, "budget" to { missingAndBudget() },
            "near competitor" to { nearCompetitor() }, "canonical" to { canonicalAliases() },
            "remembered canonical" to { rememberedCanonical() }, "unresolved" to { unresolvedMatch() },
            "deadlines" to { deadlines() }, "empty" to { emptyDownload() })
        for ((name, action) in cases) try { action() }
        catch (error: Exception) { throw AssertionError("Automatic matching case: $name", error) }
        "Automatic matching: confirmed mappings, fallback, ambiguity, budgets, deadlines, cancellation and empty download passed"
    }

    private suspend fun confirmedMapping() = Fixture().use { f ->
        f.animes["raw"] = anime("canonical", "Different Season 2", episode("target", "S02E13", "13"))
        for (offset in listOf(1.5, -0.5)) {
            f.match = match(true, Triple("raw", "target", offset))
            val result = DanmakuAutoMatch(f.api).run(media.copy(season = 0, number = 1))
            check(result.selected?.anime?.id == "canonical" && result.selected?.episode?.id == "target")
            check(result.selected?.sourceOffsetMs == (offset * 1000).toLong())
            check(f.paths.none { "search" in it })
        }
    }

    private suspend fun staleAndSuggested() = Fixture().use { f ->
        val correct = anime("right", "Story", episode("e2", "Episode 2", "2"))
        f.animes["right"] = correct; f.search = listOf(correct)
        f.match = match(true, Triple("stale", "old", 0.0))
        check(DanmakuAutoMatch(f.api).run(media).selected?.episode?.id == "e2")
        f.match = match(false, Triple("wrong", "wrong2", 0.0))
        f.animes["wrong"] = anime("wrong", "Unrelated", episode("wrong2", "Episode 2", "2"))
        check(DanmakuAutoMatch(f.api).run(media).selected?.anime?.id == "right")
        f.match = match(false, Triple("right", "e2", 1.5))
        val result = DanmakuAutoMatch(f.api).run(media)
        check(result.selected?.sourceOffsetMs == 1500L)
        check(result.suggestions.map { it.anime.id to it.episode?.id }.distinct().size == 1)
        check(f.paths.none { "comment" in it })
    }

    private suspend fun ambiguousAndTruncated() = Fixture().use { f ->
        f.animes["a"] = anime("a", "Story", episode("a2", "Episode 2", "2"))
        f.animes["b"] = anime("b", "Story", episode("b2", "Episode 2", "2"))
        f.search = f.animes.values.toList()
        f.match = match(true, Triple("a", "a2", 1.5), Triple("b", "b2", -0.5))
        val ambiguous = DanmakuAutoMatch(f.api).run(media)
        check(ambiguous.selected == null && ambiguous.suggestions.size == 2)
        f.match = match(false, Triple("a", "a2", 1.5), Triple("a", "a2", -0.5))
        val shifts = DanmakuAutoMatch(f.api).run(media)
        check(shifts.selected == null && shifts.suggestions.count { it.anime.id == "a" } == 2)
        f.match = match(true, *(1..8).map { Triple("a", "a$it", 0.0) }.toTypedArray())
        val truncated = DanmakuAutoMatch(f.api).run(media)
        check(truncated.selected == null && truncated.reason == DanmakuMatchReason.INCOMPLETE)
    }

    private suspend fun missingAndBudget() = Fixture().use { f ->
        f.animes["first"] = anime("first", "Story", episode("e1", "Episode 1", "1"), episode("e3", "Episode 3", "3"))
        f.animes["second"] = anime("second", "Story", episode("e2", "Episode 2", "2"))
        f.search = f.animes.values.toList()
        check(DanmakuAutoMatch(f.api).run(media).selected?.anime?.id == "second")
        f.search = listOf(f.animes.getValue("first"))
        check(DanmakuAutoMatch(f.api).run(media).reason == DanmakuMatchReason.NO_MATCH)
        f.search = (1..15).map { anime("candidate-$it", "Story", episode("e$it", "Episode 2", "2")) }
        f.search.forEach { f.animes[it.id] = it }; f.paths.clear()
        val limited = DanmakuAutoMatch(f.api).run(media)
        check(limited.selected == null && limited.reason == DanmakuMatchReason.INCOMPLETE)
        check(f.paths.count { "bangumi" in it } <= 6 && f.paths.count { "search" in it } == 2)
        f.search = listOf(f.animes.getValue("second")); f.paths.clear()
        f.match = match(false, *(1..6).map { Triple("missing-$it", "e", 0.0) }.toTypedArray())
        val reserved = DanmakuAutoMatch(f.api).run(media)
        check(f.paths.any { it.endsWith("/second") } && f.paths.count { "bangumi" in it } <= 6)
        check(reserved.selected == null && reserved.reason == DanmakuMatchReason.INCOMPLETE)
    }

    private suspend fun nearCompetitor() = Fixture().use { f ->
        val local = media.copy(title = "abcdefghij")
        val best = anime("0-best", "abcdefghij", episode("e2", "Episode 2", "2"))
        val missing = (1..5).map { anime("$it-missing", "abcdefghij",
            episode("e1", "Episode 1", "1"), episode("e3", "Episode 3", "3")) }
        val near = anime("z-near", "abcdefghik Season 1", episode("e2", "Episode 2", "2")).copy(type = "TV")
        f.search = listOf(best) + missing + near
        f.search.forEach { f.animes[it.id] = it }
        val decision = DanmakuAutoMatch(f.api).run(local)
        check(DanmakuMatcher.titleScore(local, near) < 0.90)
        check(decision.selected == null && decision.reason == DanmakuMatchReason.INCOMPLETE)
        check(f.paths.none { it.endsWith("/z-near") } && f.paths.count { "bangumi" in it } == 6)
    }

    private suspend fun canonicalAliases() = Fixture().use { f ->
        val canonical = anime("canonical", "Story", episode("e2", "Episode 2", "2"))
        f.animes["raw-a"] = canonical; f.animes["raw-b"] = canonical
        f.search = listOf(canonical.copy(id = "raw-a"), canonical.copy(id = "raw-b"))
        val decision = DanmakuAutoMatch(f.api).run(media)
        check(decision.selected?.anime?.id == "canonical" && decision.suggestions.size == 1)
    }

    private suspend fun rememberedCanonical() = Fixture().use { f ->
        val prefs = context.getSharedPreferences("danmaku-canonical-memory-test", Context.MODE_PRIVATE)
        val store = DanmakuStore(context, "danmaku-canonical-memory-test")
        try {
            val settings = DanmakuSourceSettings(prefs).apply { onlineSource = DanmakuOnlineSource.CUSTOM; customUrl = f.endpoint.baseUrl }
            val choice = DanmakuChoice(DanmakuSourceKind.CUSTOM, f.endpoint.identity)
            val canonical = anime("canonical", "Story", episode("e2", "Episode 2", "2"))
            f.animes["raw-a"] = canonical; f.animes["raw-b"] = canonical
            f.search = listOf(canonical.copy(id = "raw-a"), canonical.copy(id = "raw-b"))
            f.comments = """{"comments":[{"cid":1,"p":"1,1,16777215,user","m":"remembered"}]}"""
            store.remember(media.key, DanmakuMatch(choice, canonical, canonical.episodes.single(), null, 1, 2, 1500))
            val loaded = DanmakuRepository(DanmakuServices(context, settings), store, f.http).load(media) as DanmakuLoad.Loaded
            check(!loaded.automatic && loaded.match?.anime?.id == "canonical" && loaded.sourceOffsetMs == 1500L)
        } finally { store.clearCache(); store.clearMatches(); check(prefs.edit().clear().commit()) }
    }

    private suspend fun unresolvedMatch() = Fixture().use { f ->
        f.animes["a"] = anime("a", "Story", episode("a2", "Episode 2", "2"), episode("a2b", "Episode 2", "2"))
        val other = anime("b", "Story", episode("b2", "Episode 2", "2"))
        f.animes["b"] = other; f.search = listOf(other)
        f.match = match(false, Triple("a", "a2", 0.0))
        check(DanmakuAutoMatch(f.api).run(media).selected == null)
    }

    private suspend fun deadlines() = Fixture().use { f ->
        f.delayMatch = 200
        val timeout = runCatching { DanmakuAutoMatch(f.api, timeoutMs = 30).run(media) }.exceptionOrNull()
        check(timeout is DanmakuException && timeout.reason == DanmakuFailure.CONNECTION)
        f.delayMatch = 0
        f.animes["a"] = anime("a", "Story", episode("e2", "Episode 2", "2"))
        f.match = match(false, Triple("a", "e2", 0.0)); f.delaySearch = 2_000
        val partial = DanmakuAutoMatch(f.api, timeoutMs = 1_000).run(media)
        check(partial.selected == null && partial.reason == DanmakuMatchReason.INCOMPLETE)
        f.animes["a"] = anime("a", "Unrelated", episode("e2", "Episode 2", "2"))
        val incomplete = runCatching { DanmakuAutoMatch(f.api, timeoutMs = 1_000).run(media) }.exceptionOrNull()
        check(incomplete is DanmakuException && incomplete.reason == DanmakuFailure.CONNECTION)
        coroutineScope {
            val task = launch { DanmakuAutoMatch(f.api).run(media) }
            delay(30); task.cancelAndJoin()
            check(task.isCancelled)
        }
    }

    private suspend fun emptyDownload() = Fixture().use { f ->
        val prefs = context.getSharedPreferences("danmaku-auto-orchestration-test", Context.MODE_PRIVATE)
        val store = DanmakuStore(context, "danmaku-auto-orchestration-test")
        try {
            val settings = DanmakuSourceSettings(prefs).apply { onlineSource = DanmakuOnlineSource.CUSTOM; customUrl = f.endpoint.baseUrl }
            val services = DanmakuServices(context, settings)
            val right = anime("a", "Story", episode("e2", "Episode 2", "2"))
            f.animes["a"] = right; f.search = listOf(right)
            check(DanmakuRepository(services, store, f.http).load(media) is DanmakuLoad.Empty)
            check(f.paths.count { "comment" in it } == 1 && f.paths.last().endsWith("/comment/e2?format=json"))
        } finally { store.clearCache(); store.clearMatches(); check(prefs.edit().clear().commit()) }
    }

    private fun anime(id: String, title: String, vararg episodes: DanmakuEpisode) = DanmakuAnime(id, title, episodes = episodes.toList())
    private fun episode(id: String, title: String, number: String) = DanmakuEpisode(id, title, number, "opaque")
    private fun match(confirmed: Boolean, vararg rows: Triple<String, String, Double>) = JSONObject()
        .put("isMatched", confirmed).put("matches", JSONArray(rows.map { (anime, episode, shift) ->
            JSONObject().put("animeId", anime).put("episodeId", episode).put("shift", shift)
        })).toString()

    private class Fixture : AutoCloseable {
        val paths = CopyOnWriteArrayList<String>()
        val animes = mutableMapOf<String, DanmakuAnime>()
        var search = emptyList<DanmakuAnime>()
        var match: String? = null
        var comments = """{"comments":[]}"""
        @Volatile var delayMatch = 0L
        @Volatile var delaySearch = 0L
        val endpoint = DanmakuEndpoint("http://127.0.0.1:12871/fixture")
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            paths += request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
            val (code, body) = when {
                path.endsWith("/match") -> { Thread.sleep(delayMatch); if (match == null) 404 to "{}" else 200 to checkNotNull(match) }
                "/search/" in path -> { Thread.sleep(delaySearch); 200 to JSONObject().put("animes", JSONArray(search.map { json(it, false) })).toString() }
                "/bangumi/" in path -> animes[request.url.pathSegments.last()]?.let {
                    200 to JSONObject().put("bangumi", json(it, true)).toString()
                } ?: (404 to "{}")
                else -> 200 to comments
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        val api = DanmakuApi(http, endpoint)
        private fun json(anime: DanmakuAnime, withEpisodes: Boolean): JSONObject {
            val json = JSONObject().put("animeId", anime.id).put("animeTitle", anime.title)
                .put("type", anime.type).put("year", anime.year)
            if (withEpisodes) json.put("episodes", JSONArray(anime.episodes.map {
                JSONObject().put("episodeId", it.id).put("episodeTitle", it.title).put("episodeNumber", it.number).put("seasonId", it.seasonId)
            }))
            return json
        }
        override fun close() { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }
}

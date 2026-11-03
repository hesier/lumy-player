package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.content.Context
import com.hesier.lumy.LumyApp
import com.hesier.lumy.data.DanmakuServices
import com.hesier.lumy.net.danmaku.DanmakuApi
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Explicit opt-in only: one request per route, using build configuration without printing secrets. */
internal class DanmakuOfficialLiveTest(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val requests = ConcurrentHashMap<String, AtomicInteger>()

    fun run(keyword: String): String {
        require(keyword.isNotBlank())
        instrumentation.runOnMainSync { LumyApp.instance.currentEntry }
        val global = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val sessions = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        val savedGlobal = global.all.toMap()
        val savedSessions = sessions.all.toMap()
        val preferences = context.getSharedPreferences("danmaku-official-live-test", Context.MODE_PRIVATE)
        val endpoint = DanmakuServices(context).official()
        check(endpoint.official && endpoint.identity == "official:dandanplay" && endpoint.proxyToken != null)
        val http = DanmakuServices.http.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val family = request.url.pathSegments.firstOrNull { it in listOf("search", "bangumi", "match", "comment") }
                ?: error("Unexpected official request route")
            requests.getOrPut(family) { AtomicInteger() }.incrementAndGet()
            check(request.header("Authorization")?.startsWith("Bearer ") == true)
            check(listOf("X-AppSecret", "X-AppId", "X-Emby-Token").all { request.header(it) == null })
            chain.proceed(request)
        }.build()
        try {
            check(preferences.edit().clear().commit())
            val api = DanmakuApi(http, endpoint)
            val (comments, matchCount) = runBlocking {
                val animes = api.search(keyword)
                val exact = animes.filter { it.title == keyword }
                val anime = exact.singleOrNull() ?: error("Official search requires an unambiguous exact title")
                val details = api.details(anime.id)
                val episode = details.episodes.singleOrNull { it.number?.trim()?.toDoubleOrNull() == 1.0 }
                    ?: error("Official detail requires a unique first episode")
                val matching = api.match("$keyword S01E01")
                api.comments(episode.id) to (matching?.candidates?.size ?: 0)
            }
            check(comments.isNotEmpty()) { "Official comments were empty" }
            check(requests.keys == setOf("search", "bangumi", "match", "comment") && requests.values.all { it.get() == 1 })
            val visible = DanmakuPluginLiveOverlay(instrumentation, preferences).use { it.run(comments, 0) }
            return "Official relay: requests=4, comments=${comments.size}, matchCandidates=$matchCount, " +
                "firstVisible=${visible.first}, seekVisible=${visible.second}; bearer isolation, display, seek and cleanup passed"
        } finally {
            check(preferences.edit().clear().commit())
            check(savedGlobal == global.all && savedSessions == sessions.all) { "Official test changed user preferences or sessions" }
        }
    }
}

package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import com.hesier.lumy.LumyApp
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.data.*
import com.hesier.lumy.jellyfin.JellyfinMediaServer
import com.hesier.lumy.net.danmaku.*
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.CopyOnWriteArrayList

/** Read-only plugin downloads use saved credentials; no remote video or playback reporting is used. */
class DanmakuPluginLiveTest(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val preferences = context.getSharedPreferences("danmaku-plugin-live-test", Context.MODE_PRIVATE)
    private val store = DanmakuStore(context, "danmaku-plugin-live-test")
    private val events = CopyOnWriteArrayList<String>()
    @Volatile private var pluginStatus = 0
    @Volatile private var authValid = false
    @Volatile private var onlineAuthIsolated = true
    private var pluginFailure: DanmakuFailure? = null

    fun runAll(arguments: Bundle): String {
        val kind = ServerKind.valueOf(checkNotNull(arguments.getString("danmaku_plugin_kind")))
        require(kind == ServerKind.JELLYFIN || kind == ServerKind.EMBY)
        val item = checkNotNull(arguments.getString("danmaku_plugin_item"))
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val sessions = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        instrumentation.waitForIdleSync()
        main { LumyApp.instance.jellyfin; LumyApp.instance.currentEntry }
        val savedSettings = settings.all.toMap()
        val savedSessions = sessions.all.toMap()
        val fixture = DanmakuPlayerFlowServer().apply {
            matchBody = """{"success":true,"isMatched":true,"matches":[{"animeId":"A","episodeId":"A2"}]}"""
        }
        try {
            check(preferences.edit().clear().commit())
            store.clearCache(); store.clearMatches()
            val source = DanmakuSourceSettings(preferences).apply {
                onlineSource = DanmakuOnlineSource.CUSTOM
                customUrl = fixture.endpoint
            }
            val services = DanmakuServices(context, source)
            val saved = SessionStore(context).servers.first { it.kind == kind }
            val server = JellyfinMediaServer(saved.session, LumyApp.instance.jellyfin)
            val detail = try { runBlocking { server.detail(item) } } catch (error: Exception) {
                error("Plugin metadata read failed: ${error.javaClass.simpleName}")
            }
            val version = detail.versions.firstOrNull() ?: error("Selected plugin item needs a playable version")
            val media = DanmakuMedia.from(server, detail, version, saved.id)
            val plugin = checkNotNull(media.plugin)
            val manual = if (kind == ServerKind.JELLYFIN) seedManual(media, services) else null
            val http = observedHttp(plugin.url, plugin.headers, saved.session.token, fixture.endpoint)
            val repository = DanmakuRepository(services, store, http)
            val loaded = runBlocking { repository.load(media) }
            pluginFailure = repository.serverFailure
            check(pluginStatus == 200 && authValid) { "Plugin HTTP/authentication validation failed" }
            val result = loaded as? DanmakuLoad.Loaded ?: error("Plugin integration did not load comments")
            validateSource(kind, result, fixture)
            manual?.let { check(store.match(media.key, it.choice.identity) == it) { "Plugin loading changed the stored online manual match" } }
            val visible = DanmakuPluginLiveOverlay(instrumentation, preferences).use { it.run(result.comments, result.sourceOffsetMs) }
            return "Plugin integration: kind=$kind normalized=${result.comments.size} " +
                "firstVisible=${visible.first} seekVisible=${visible.second} source=${result.source.kind}; authentication, priority/fallback and cleanup passed"
        } finally {
            fixture.close()
            store.clearCache(); store.clearMatches(); check(preferences.edit().clear().commit())
            check(savedSettings == settings.all && savedSessions == sessions.all) { "Plugin test changed user settings or sessions" }
        }
    }

    private fun seedManual(media: DanmakuMedia, services: DanmakuServices): DanmakuMatch {
        val choice = DanmakuChoice(DanmakuSourceKind.CUSTOM, services.endpoint.identity)
        val anime = DanmakuAnime("A", "Flow A", episodes = emptyList())
        val episode = DanmakuEpisode("A2", "Flow A episode 2", "2", "opaque")
        return DanmakuMatch(choice, anime, episode, media.year, media.season, media.number).also { store.remember(media.key, it) }
    }

    private fun observedHttp(pluginUrl: String, headers: Map<String, String>, token: String, onlineUrl: String) =
        DanmakuServices.http.newBuilder().addNetworkInterceptor { chain ->
            val request = chain.request()
            val pluginRequest = request.url.toString() == pluginUrl
            if (pluginRequest) {
                events.add("plugin")
                authValid = token.isNotBlank() && headers.all { (key, value) -> request.header(key) == value } &&
                    headers.any { (key, value) -> (key.equals("Authorization", true) || key.equals("X-Emby-Token", true)) && value.contains(token) }
            } else if (request.url.host == onlineUrl.toHttpUrl().host && request.url.port == onlineUrl.toHttpUrl().port) {
                events.add("online")
                onlineAuthIsolated = onlineAuthIsolated && request.header("Authorization") == null && request.header("X-Emby-Token") == null
            } else error("Unexpected plugin integration network target")
            chain.proceed(request).also { if (pluginRequest) pluginStatus = it.code }
        }.build()

    private fun validateSource(kind: ServerKind, result: DanmakuLoad.Loaded, fixture: DanmakuPlayerFlowServer) {
        check(result.comments.isNotEmpty())
        check(events.firstOrNull() == "plugin") { "Online request preceded the installed plugin" }
        if (kind == ServerKind.JELLYFIN) {
            check(result.source.kind == DanmakuSourceKind.SERVER && result.match == null && !result.automatic) {
                "Expected plugin source; actual=${result.source.kind}, failure=$pluginFailure"
            }
            check(events.none { it == "online" } && fixture.paths.isEmpty()) { "Plugin loading reached an online source" }
        } else {
            check(pluginFailure == null) { "Empty Emby response failed parsing: $pluginFailure" }
            check(result.source.kind == DanmakuSourceKind.CUSTOM && result.automatic && events.any { it == "online" })
            check(onlineAuthIsolated) { "Media authentication leaked to the independent online source" }
        }
    }

    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}

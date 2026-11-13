package com.hesier.lumy.tv

import android.content.Context
import android.os.Build
import androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms
import com.hesier.lumy.LumyApp
import com.hesier.lumy.core.Hub
import com.hesier.lumy.core.HubLabel
import com.hesier.lumy.core.HubStyle
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.PlaybackState
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import com.hesier.lumy.data.SavedServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/** Exercises Plex privacy policy without touching the launcher or contacting a server. */
class WatchNextPlexPolicyTest(private val base: Context) {
    private val app get() = LumyApp.instance
    private val serverField = LumyApp::class.java.getDeclaredField("server").apply { isAccessible = true }
    private val resume = item("plex-policy-resume")
    private var pendingHome: CompletableDeferred<Continuation<Any?>>? = null
    private var homeCalls = 0

    suspend fun runAll() {
        check(Build.VERSION.SDK_INT >= 29) { "Isolated Watch Next tests require API 29+" }
        val context = WatchNextTestContext(base)
        val originalId = app.store.currentId
        val originalServer = serverField.get(app)
        val originalEnabled = app.settings.watchNextEnabled
        val originalAutoSignIn = app.settings.plexHomeAutoSignIn
        val entries = ServerKind.entries.map { kind ->
            SavedServer("plex-policy-${UUID.randomUUID()}", ServerSession(
                kind, "plex-policy-${kind.name}", "Policy fixture", "http://127.0.0.1", "fixture-token",
            ))
        }
        try {
            entries.forEach(app.store::add)
            app.settings.watchNextEnabled = true
            app.settings.plexHomeAutoSignIn = true
            val plex = select(context, entries.single { it.kind == ServerKind.PLEX })
            WatchNextManager.refresh(context)
            assertCards(context, resume.id)
            rejectsLateResponses(context, plex, reopenBeforeResponse = false)
            rejectsLateResponses(context, plex, reopenBeforeResponse = true)
            globalDisableWins(context, plex)
            for (entry in entries.filter { it.kind != ServerKind.PLEX }) {
                otherServerUnaffected(context, select(context, entry))
            }
        } finally {
            pendingHome = null
            try {
                WatchNextManager.clearAll(context)
            } finally {
                entries.forEach { app.store.remove(it.id) }
                app.store.currentId = originalId
                app.settings.watchNextEnabled = originalEnabled
                app.settings.plexHomeAutoSignIn = originalAutoSignIn
                serverField.set(app, originalServer)
                WatchNextManager.onServerChanged(context)
                check(app.getSharedPreferences("session", Context.MODE_PRIVATE).edit().commit())
                check(app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().commit())
            }
        }
    }

    private suspend fun select(context: WatchNextTestContext, entry: SavedServer): MediaServer {
        app.store.currentId = entry.id
        val server = fakeServer(entry.session)
        serverField.set(app, server)
        WatchNextManager.onServerChanged(context)
        WatchNextAccess.run(context) {}.getOrThrow()
        return server
    }

    private fun fakeServer(session: ServerSession): MediaServer = Proxy.newProxyInstance(
        MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getSession" -> session
            "homeHubs" -> {
                homeCalls++
                val pending = pendingHome
                if (pending == null) hubs(listOf(resume)) else {
                    @Suppress("UNCHECKED_CAST")
                    val continuation = args!!.last() as Continuation<Any?>
                    pending.complete(continuation)
                    COROUTINE_SUSPENDED
                }
            }
            "toString" -> "Plex policy fixture"
            else -> error("Unexpected fixture call: ${method.name}")
        }
    } as MediaServer

    private suspend fun rejectsLateResponses(
        context: WatchNextTestContext, server: MediaServer, reopenBeforeResponse: Boolean,
    ) = coroutineScope {
        val old = checkNotNull(WatchNextManager.captureSession(server))
        val waiting = CompletableDeferred<Continuation<Any?>>()
        pendingHome = waiting
        val request = async { WatchNextManager.refresh(context) }
        var reply: Continuation<Any?>? = null
        var replied = false
        try {
            reply = withTimeout(5_000) { waiting.await() }
            pendingHome = null
            WatchNextManager.setPlexHomeAutoSignIn(context, false)
            check(!app.settings.plexHomeAutoSignIn && app.settings.watchNextEnabled)
            check(!WatchNextManager.isEnabled())
            check(WatchNextManager.captureSession(server) == null)
            assertCards(context)
            staleWork(context, server, old)
            assertCards(context)
            if (reopenBeforeResponse) WatchNextManager.setPlexHomeAutoSignIn(context, true)
            // Empty stale responses must not erase restored cards; nonempty ones must not revive disabled cards.
            val response = if (reopenBeforeResponse) emptyList() else listOf(item("late-home"))
            reply.resumeWith(Result.success(hubs(response)))
            replied = true
            request.await()
            if (reopenBeforeResponse) assertCards(context, resume.id) else assertCards(context)
            if (!reopenBeforeResponse) WatchNextManager.setPlexHomeAutoSignIn(context, true)
            check(app.settings.plexHomeAutoSignIn && app.settings.watchNextEnabled)
            check(WatchNextManager.isEnabled())
            check(WatchNextManager.captureSession(server) != null)
            assertCards(context, resume.id)
            staleWork(context, server, old)
            WatchNextManager.syncContinueWatching(context, server, emptyList(), old)
            assertCards(context, resume.id)
        } finally {
            pendingHome = null
            if (!replied) {
                val outstanding = reply ?: if (waiting.isCompleted) waiting.await() else null
                outstanding?.resumeWith(Result.success(emptyList<Hub>()))
            }
            request.cancelAndJoin()
        }
    }

    private suspend fun staleWork(context: Context, server: MediaServer, old: WatchNextSession) {
        WatchNextManager.onPlaybackProgress(context, server, item("late-playback"), 120_000L,
            600_000L, null, PlaybackState.STOPPED, old)
        WatchNextManager.syncContinueWatching(context, server, listOf(item("late-home")), old)
    }

    private suspend fun globalDisableWins(context: WatchNextTestContext, server: MediaServer) {
        WatchNextManager.setEnabled(context, false)
        WatchNextManager.setPlexHomeAutoSignIn(context, false)
        val calls = homeCalls
        WatchNextManager.setPlexHomeAutoSignIn(context, true)
        check(app.settings.plexHomeAutoSignIn && !app.settings.watchNextEnabled)
        check(!WatchNextManager.isEnabled())
        check(WatchNextManager.captureSession(server) == null)
        check(homeCalls == calls) { "Plex auto sign-in must preserve the global disable preference" }
        assertCards(context)
        WatchNextManager.setEnabled(context, true)
        assertCards(context, resume.id)
    }

    private suspend fun otherServerUnaffected(context: WatchNextTestContext, server: MediaServer) {
        WatchNextManager.refresh(context)
        assertCards(context, resume.id)
        val old = checkNotNull(WatchNextManager.captureSession(server))
        val originalRow = context.provider.rows.keys.single()
        val calls = homeCalls
        for (enabled in listOf(false, true)) {
            WatchNextManager.setPlexHomeAutoSignIn(context, enabled)
            check(app.settings.plexHomeAutoSignIn == enabled && app.settings.watchNextEnabled)
            check(WatchNextManager.isEnabled())
            check(WatchNextManager.captureSession(server) != null)
            check(WatchNextAccess.isCurrent(old)) { "Plex preference must not invalidate another server" }
            check(context.provider.rows.keys.single() == originalRow)
            check(homeCalls == calls) { "Plex preference must not refresh another server" }
            assertCards(context, resume.id)
            val position = if (enabled) 240_000L else 180_000L
            WatchNextManager.onPlaybackProgress(context, server, resume, position,
                600_000L, null, PlaybackState.STOPPED, old)
            check(context.provider.rows.values.single()
                .getAsInteger(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS) == position.toInt())
        }
    }

    private fun assertCards(context: WatchNextTestContext, vararg ids: String) {
        val actual = context.provider.rows.values.map { it.getAsString(WatchNextPrograms.COLUMN_CONTENT_ID) }.toSet()
        check(ids.toSet() == actual) { "Expected cards ${ids.toList()}, got $actual" }
    }

    private fun hubs(items: List<MediaItem>) = listOf(
        Hub("resume", "Continue Watching", HubStyle.LANDSCAPE, items, HubLabel.ContinueWatching),
    )

    private fun item(id: String) = MediaItem(
        id, MediaType.MOVIE, id, durationMs = 600_000L, viewOffsetMs = 120_000L,
    )
}

package com.hesier.lumy.tv

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms
import com.hesier.lumy.LumyApp
import com.hesier.lumy.core.MediaDetail
import com.hesier.lumy.core.HttpStatusException
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.PlaybackState
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import com.hesier.lumy.data.SavedServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

class WatchNextManagerTest(base: Context) {
    private val context = if (Build.VERSION.SDK_INT >= 29) WatchNextTestContext(base) else null
    private val session = ServerSession(
        kind = ServerKind.JELLYFIN, serverId = "watch-next-test", name = "Test Server",
        baseUrl = "http://127.0.0.1", token = "test-token", userId = "user-1",
    )
    private val first = SavedServer("watch-next-test-account-1", session)
    private val second = SavedServer("watch-next-test-account-2", session.copy(userId = "user-2"))

    private val details = mutableMapOf<String, MediaItem>()
    private var detailFailure: Exception? = null

    private fun fakeServer(session: ServerSession): MediaServer = Proxy.newProxyInstance(
        MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getSession" -> session
            "imageUrl" -> "https://example.com/poster.jpg"
            "detail" -> {
                val failure = detailFailure
                if (failure != null) {
                    @Suppress("UNCHECKED_CAST")
                    val continuation = args!!.last() as Continuation<Any?>
                    continuation.resumeWith(Result.failure(failure))
                    COROUTINE_SUSPENDED
                } else {
                    val id = args!![0] as String
                    MediaDetail(details[id] ?: item(id, MediaType.EPISODE))
                }
            }
            else -> null
        }
    } as MediaServer

    private fun item(id: String, type: MediaType = MediaType.MOVIE) = MediaItem(
        id = id, type = type, title = id, durationMs = 600_000L,
        viewOffsetMs = 120_000L, poster = "/poster.jpg",
    )

    fun runAll() = runBlocking {
        val context = checkNotNull(context) { "Isolated Watch Next tests require API 29+" }
        val store = LumyApp.instance.store
        val originalId = store.currentId
        val originalEnabled = LumyApp.instance.settings.watchNextEnabled
        LumyApp.instance.settings.watchNextEnabled = true
        store.add(first)
        store.add(second)
        try {
            store.currentId = first.id
            WatchNextManager.onServerChanged(context)
            WatchNextManager.clearAll(context)
            playbackLifecycle(context)
            accountAndGeneration(context)
            switchDuringWrite(context)
            concurrentWrites(context)
            orderedUpdates(context)
            nextLifecycle(context)
            resumeContext(context)
            containerTombstones(context)
            disabledPublishing(context)
            WatchNextManager.clearAll(context)
            check(context.provider.rows.isEmpty()) { "clearAll must remove provider rows" }
            check(context.getSharedPreferences("watch_next_programs", Context.MODE_PRIVATE).all.isEmpty())
        } finally {
            store.remove(first.id)
            store.remove(second.id)
            store.currentId = originalId
            LumyApp.instance.settings.watchNextEnabled = originalEnabled
            WatchNextManager.onServerChanged(context)
            WatchNextManager.clearAll(context)
            check(LumyApp.instance.getSharedPreferences("session", Context.MODE_PRIVATE).edit().commit())
            check(LumyApp.instance.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().commit())
        }
    }

    private suspend fun playbackLifecycle(context: WatchNextTestContext) {
        val server = fakeServer(session)
        val movie = item("movie")
        progress(context, server, movie, 120_000L)
        val id = context.provider.rows.keys.single()
        progress(context, server, movie, 180_000L)
        check(context.provider.rows.keys.single() == id) { "Progress must update the existing row" }
        check(context.provider.rows[id]?.getAsInteger(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS) == 180_000)
        progress(context, server, movie, 590_000L)
        check(context.provider.rows.isEmpty()) { "Completed movie must be removed" }
        val next = item("ep-2", MediaType.EPISODE)
        progress(context, server, item("ep-1", MediaType.EPISODE), 590_000L, next)
        WatchNextManager.syncContinueWatching(context, server, listOf(item("resume")))
        check(context.provider.rows.values.any { it.getAsString(WatchNextPrograms.COLUMN_CONTENT_ID) == next.id })
        WatchNextManager.syncContinueWatching(context, server, emptyList())
        check(context.provider.rows.size == 1) { "Empty resume list must preserve NEXT but remove CONTINUE" }
        WatchNextManager.removeItem(context, next.id, WatchNextManager.captureSession(server))
        check(context.provider.rows.isEmpty()) { "Marking NEXT watched must remove it" }
    }

    private suspend fun accountAndGeneration(context: WatchNextTestContext) {
        val store = LumyApp.instance.store
        val oldServer = fakeServer(session)
        val stale = WatchNextManager.captureSession(oldServer)
        store.currentId = second.id
        WatchNextManager.onServerChanged(context)
        val server = fakeServer(second.session)
        progress(context, server, item("account-2"), 120_000L)
        val row = context.provider.rows.values.single()
        val intent = Intent.parseUri(row.getAsString(WatchNextPrograms.COLUMN_INTENT_URI), 0)
        check(intent.getStringExtra("server_id") == second.id) { "Same remote server must keep the selected account" }
        WatchNextManager.onPlaybackProgress(
            context, oldServer, item("late"), 120_000L, 600_000L, null, PlaybackState.STOPPED, stale,
        )
        WatchNextAccess.run(context) {}
        check(context.provider.rows.values.single().getAsString(WatchNextPrograms.COLUMN_CONTENT_ID) == "account-2")
        store.currentId = first.id
        WatchNextManager.onServerChanged(context)
        WatchNextManager.syncContinueWatching(context, oldServer, listOf(item("late-home")), stale)
        WatchNextManager.removeItem(context, "account-2", stale)
        progress(context, oldServer, item("fresh"), 120_000L)
        check(context.provider.rows.values.single().getAsString(WatchNextPrograms.COLUMN_CONTENT_ID) == "fresh") {
            "Switching back must reject the old generation and clear the previous account"
        }
        WatchNextManager.clearAll(context)
    }

    private suspend fun switchDuringWrite(context: WatchNextTestContext) {
        val store = LumyApp.instance.store
        context.provider.beforeInsert = {
            store.currentId = second.id
            WatchNextAccess.invalidate()
        }
        progress(context, fakeServer(first.session), item("switching"), 120_000L)
        progress(context, fakeServer(second.session), item("new-account"), 120_000L)
        WatchNextAccess.run(context) {}
        check(context.provider.rows.values.single().getAsString(WatchNextPrograms.COLUMN_CONTENT_ID) == "new-account") {
            "Cleanup queued during an insert must remove old data and keep the new account's row"
        }
        store.currentId = first.id
        WatchNextManager.onServerChanged(context)
        WatchNextManager.clearAll(context)
    }

    private suspend fun concurrentWrites(context: WatchNextTestContext) = coroutineScope {
        val server = fakeServer(session)
        (1..20).map { async(Dispatchers.IO) { progress(context, server, item("concurrent"), 120_000L) } }.awaitAll()
        check(context.provider.rows.size == 1) { "Concurrent progress must not insert duplicate rows" }
    }

    private suspend fun orderedUpdates(context: WatchNextTestContext) {
        WatchNextManager.clearAll(context)
        val server = fakeServer(session)
        val movie = item("ordered")
        val oldSnapshot = WatchNextManager.captureSession(server)
        WatchNextManager.syncContinueWatching(context, server, emptyList())
        WatchNextManager.syncContinueWatching(context, server, listOf(movie), oldSnapshot)
        check(context.provider.rows.isEmpty()) { "A newer empty snapshot must reject older inserts" }
        val old = WatchNextManager.captureSession(server)
        progress(context, server, movie, 240_000L)
        WatchNextManager.onPlaybackProgress(context, server, movie, 120_000L, 600_000L,
            null, PlaybackState.PLAYING, old)
        check(context.provider.rows.values.single().getAsInteger(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS) == 240_000)
        WatchNextManager.syncContinueWatching(context, server, emptyList(), old)
        check(context.provider.rows.size == 1) { "Old home response must not delete newer progress" }
        val beforeCompletion = WatchNextManager.captureSession(server)
        progress(context, server, movie, 590_000L)
        WatchNextManager.onPlaybackProgress(context, server, movie, 120_000L, 600_000L,
            null, PlaybackState.PLAYING, beforeCompletion)
        WatchNextManager.syncContinueWatching(context, server, listOf(movie.copy(viewOffsetMs = 590_000L)))
        check(context.provider.rows.isEmpty()) { "Neither late progress nor home sync may restore completed content" }
        progress(context, server, movie, 60_000L)
        check(context.provider.rows.size == 1) { "A deliberate later rewind must still publish" }
        progress(context, server, movie, 5_000L)
        check(context.provider.rows.values.single().getAsInteger(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS) == 5_000)
        progress(context, server, movie, 0L)
        val uri = context.provider.rows.values.single().getAsString(WatchNextPrograms.COLUMN_INTENT_URI)
        check(Intent.parseUri(uri, 0).getLongExtra("start", -1L) == 0L) { "Existing cards must follow a rewind to the start" }
        WatchNextManager.clearAll(context)
        progress(context, server, movie, 5_000L)
        check(context.provider.rows.isEmpty()) { "The minimum position must still apply to first publication" }
    }

    private suspend fun nextLifecycle(context: WatchNextTestContext) {
        val server = fakeServer(session)
        val ep1 = item("series-1", MediaType.EPISODE).copy(grandparentId = "show", parentId = "season")
        val ep2 = ep1.copy(id = "series-2")
        val ep3 = ep1.copy(id = "series-3")
        progress(context, server, ep1, 590_000L, ep2)
        detailFailure = java.io.IOException("Temporary failure")
        WatchNextManager.syncContinueWatching(context, server, emptyList())
        check(context.provider.rows.size == 1) { "A NEXT validation error must retain the card" }
        detailFailure = null
        progress(context, server, ep3, 120_000L)
        check(context.provider.rows.values.single().getAsString(WatchNextPrograms.COLUMN_CONTENT_ID) == ep3.id)
        progress(context, server, ep3, 590_000L, ep2)
        details[ep2.id] = ep2.copy(watched = true)
        WatchNextManager.syncContinueWatching(context, server, emptyList())
        check(context.provider.rows.isEmpty()) { "Externally watched NEXT must be removed" }
        details.clear()
        progress(context, server, ep1, 590_000L, ep2)
        detailFailure = HttpStatusException(404, "Removed")
        WatchNextManager.syncContinueWatching(context, server, emptyList())
        check(context.provider.rows.isEmpty()) { "Deleted NEXT must be removed" }
        detailFailure = null
        progress(context, server, ep1, 120_000L)
        WatchNextManager.removeWatched(context, item("season", MediaType.SEASON), WatchNextManager.captureSession(server))
        check(context.provider.rows.isEmpty()) { "Marking a season watched must remove its episodes" }
    }

    private suspend fun resumeContext(context: WatchNextTestContext) {
        val server = fakeServer(session)
        val movie = item("versioned")
        WatchNextManager.onPlaybackProgress(context, server, movie, 120_000L, 600_000L, null,
            PlaybackState.STOPPED, resume = WatchNextResume("second-version", "playlist"))
        WatchNextManager.syncContinueWatching(context, server, listOf(movie))
        val uri = context.provider.rows.values.single().getAsString(WatchNextPrograms.COLUMN_INTENT_URI)
        val intent = Intent.parseUri(uri, 0)
        check(intent.getStringExtra("watch_next_version_id") == "second-version")
        check(intent.getStringExtra("playlist") == "playlist")
        WatchNextManager.clearAll(context)
    }

    private suspend fun containerTombstones(context: WatchNextTestContext) {
        val server = fakeServer(session)
        val episode = item("container-episode", MediaType.EPISODE).copy(grandparentId = "show", parentId = "season")
        for (container in listOf(item("show", MediaType.SHOW), item("season", MediaType.SEASON))) {
            WatchNextManager.clearAll(context)
            val old = WatchNextManager.captureSession(server)
            WatchNextManager.removeWatched(context, container, WatchNextManager.captureSession(server))
            WatchNextManager.onPlaybackProgress(context, server, episode, 120_000L, 600_000L,
                null, PlaybackState.STOPPED, old)
            check(context.provider.rows.isEmpty()) { "Watched container must reject unpublished old episodes" }
        }
    }

    private suspend fun disabledPublishing(context: WatchNextTestContext) {
        val server = fakeServer(session)
        val old = WatchNextManager.captureSession(server)
        progress(context, server, item("enabled"), 120_000L)
        WatchNextManager.setEnabled(context, false)
        check(context.provider.rows.isEmpty())
        check(WatchNextManager.captureSession(server) == null)
        WatchNextManager.onPlaybackProgress(context, server, item("disabled"), 120_000L, 600_000L,
            null, PlaybackState.STOPPED, old)
        check(context.provider.rows.isEmpty()) { "Disabling must reject already queued publications" }
        LumyApp.instance.settings.watchNextEnabled = true
        WatchNextManager.onServerChanged(context)
    }

    private suspend fun progress(
        context: Context, server: MediaServer, item: MediaItem, positionMs: Long, next: MediaItem? = null,
    ) = WatchNextManager.onPlaybackProgress(
        context, server, item, positionMs, 600_000L, next, PlaybackState.STOPPED,
    )
}

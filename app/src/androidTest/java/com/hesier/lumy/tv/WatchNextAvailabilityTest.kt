package com.hesier.lumy.tv

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.core.Hub
import com.hesier.lumy.core.HubLabel
import com.hesier.lumy.core.HubStyle
import com.hesier.lumy.core.MediaDetail
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.PlaybackState
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import com.hesier.lumy.data.SavedServer
import com.hesier.lumy.ui.settings.SettingsActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID

/** Exercises capability loss against isolated providers, then real settings focus without mutating cards. */
class WatchNextAvailabilityTest(private val instrumentation: Instrumentation) {
    private val app get() = LumyApp.instance
    private val context get() = instrumentation.targetContext
    private val item = MediaItem("availability-movie", MediaType.MOVIE, "Availability fixture",
        thumb = "/poster", durationMs = 600_000L, viewOffsetMs = 120_000L)
    private val calls = IntArray(3)
    private lateinit var server: MediaServer
    private lateinit var activity: SettingsActivity
    private var hiddenCount = 0

    fun runAll(): String = runBlocking {
        instrumentation.waitForIdleSync()
        check(Build.VERSION.SDK_INT >= 29) { "Isolated provider tests require API 29+" }
        val field = LumyApp::class.java.getDeclaredField("server").apply { isAccessible = true }
        val originalServer = field.get(app)
        val originalId = app.store.currentId
        val originalEnabled = app.settings.watchNextEnabled
        val session = ServerSession(ServerKind.JELLYFIN, "availability-fixture", "Availability fixture",
            "http://127.0.0.1", "fixture-token", userId = "availability-user")
        val entry = SavedServer("availability-${UUID.randomUUID()}", session)
        try {
            app.store.add(entry)
            app.store.currentId = entry.id
            app.settings.watchNextEnabled = true
            server = fakeServer(session)
            field.set(app, server)
            verifyEmptyCursor()
            for (kind in listOf("null", "table", "permission", "lost-null", "lost-permission", "write", "concurrent-write")) {
                verifyUnavailable(kind)
            }
        } finally {
            app.store.remove(entry.id)
            app.store.currentId = originalId
            app.settings.watchNextEnabled = originalEnabled
            field.set(app, originalServer)
            check(context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().commit())
            check(context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().commit())
        }
        verifyHiddenSettings()
        verifyDiscoveredSettings()
        "Watch Next availability: cached empty cursor, missing provider/table, denied/lost access, denied writes, silent no-retry, hidden settings and D-pad boundaries passed"
    }

    private fun fakeServer(session: ServerSession): MediaServer = Proxy.newProxyInstance(
        MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getSession" -> session
            "getSupportsFavorites", "getEmbedsSubtitles" -> false
            "imageUrl" -> { calls[0]++; "https://example.invalid/poster.jpg" }
            "homeHubs" -> {
                calls[1]++
                listOf(Hub("resume", "Continue Watching", HubStyle.LANDSCAPE, listOf(item), HubLabel.ContinueWatching))
            }
            "detail" -> { calls[2]++; MediaDetail(item) }
            "toString" -> "Availability fixture"
            else -> error("Unexpected fixture call: ${method.name}")
        }
    } as MediaServer

    private fun isolated(): WatchNextTestContext {
        val name = "watch-next-availability-${UUID.randomUUID()}"
        return object : WatchNextTestContext(context) {
            override fun getFilesDir() = File(context.filesDir, name).apply { mkdirs() }
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences =
                context.getSharedPreferences("${name}_$key", mode)
        }
    }

    private suspend fun verifyEmptyCursor() {
        val isolated = isolated()
        try {
            repeat(4) { check(WatchNextManager.isAvailable(isolated)) }
            check(WatchNextSupport.isSupported(isolated))
            check(isolated.provider.queries == 1 && isolated.provider.writes == 0) {
                "Supported empty cursor must be probed only once"
            }
        } finally { cleanup(isolated) }
    }

    private suspend fun verifyUnavailable(kind: String) {
        val isolated = isolated()
        val provider = isolated.provider
        try {
            if (kind.startsWith("lost") || kind.endsWith("write")) check(WatchNextManager.isAvailable(isolated))
            when (kind) {
                "null", "lost-null" -> provider.nullQueries = true
                "table" -> provider.queryFailure = IllegalArgumentException("No Watch Next table")
                "permission", "lost-permission" -> provider.queryFailure = SecurityException("Watch Next denied")
                "write", "concurrent-write" -> provider.writeFailure = SecurityException("Watch Next insert denied")
            }
            if (kind == "concurrent-write") coroutineScope {
                (1..8).map { async(Dispatchers.IO) { progress(isolated) } }.awaitAll()
            } else if (kind.startsWith("lost") || kind == "write") progress(isolated)
            else check(!WatchNextManager.isAvailable(isolated))
            check(WatchNextSupport.isUnavailable(isolated)) { "$kind must be cached unavailable" }
            check(!WatchNextSupport.isSupported(isolated))
            if (kind.endsWith("write")) check(provider.writes == 1) { "Expected one rejected insertion" }
            verifyNoRetry(isolated, kind)
        } finally { cleanup(isolated) }
    }

    private suspend fun verifyNoRetry(isolated: WatchNextTestContext, kind: String) {
        val queries = isolated.provider.queries
        val writes = isolated.provider.writes
        val originalCalls = calls.toList()
        repeat(3) {
            check(!WatchNextSupport.ensure(isolated))
            progress(isolated)
            WatchNextManager.syncContinueWatching(isolated, server, listOf(item))
            WatchNextManager.refresh(isolated)
            WatchNextManager.clearAll(isolated)
            WatchNextManager.onServerChanged(isolated)
        }
        check(isolated.provider.queries == queries && isolated.provider.writes == writes) {
            "$kind retried provider access after becoming unavailable"
        }
        check(calls.toList() == originalCalls) { "$kind requested artwork/home/detail after becoming unavailable" }
    }

    private suspend fun progress(isolated: WatchNextTestContext) {
        WatchNextManager.onPlaybackProgress(isolated, server, item, 120_000L,
            600_000L, null, PlaybackState.PAUSED)
    }

    private fun cleanup(isolated: WatchNextTestContext) {
        isolated.getSharedPreferences("watch_next_programs", Context.MODE_PRIVATE).edit().clear().commit()
        isolated.filesDir.deleteRecursively()
        resetSupport(isolated)
    }

    private suspend fun verifyHiddenSettings() {
        check(WatchNextManager.isAvailable(app)) { "Settings visibility test requires a supported real TV provider" }
        check(WatchNextSupport.onFailure(app, SecurityException("Availability UI test")))
        check(!WatchNextManager.isSupported())
        var opened = false
        try {
            activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as SettingsActivity
            opened = true
            await { main { activity.hasWindowFocus() && position(R.id.categories) == 0 } }
            hiddenCount = main { activity.findViewById<RecyclerView>(R.id.list).adapter!!.itemCount }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertPosition(R.id.list, 0)
            key(KeyEvent.KEYCODE_DPAD_UP)
            assertPosition(R.id.list, 0)
            verifyRowsAndBottom()
        } finally {
            if (opened) main { activity.finishAndRemoveTask() }
            instrumentation.waitForIdleSync()
            resetSupport(app)
            check(WatchNextManager.isAvailable(app)) { "Could not restore the real capability cache" }
        }
    }

    private suspend fun verifyDiscoveredSettings() {
        resetSupport(app)
        check(!WatchNextManager.isSupported()) { "Discovery must begin with an unknown capability cache" }
        var opened = false
        try {
            activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as SettingsActivity
            opened = true
            await { main {
                WatchNextManager.isSupported() &&
                    activity.findViewById<RecyclerView>(R.id.list).adapter!!.itemCount == hiddenCount + 3 &&
                    activity.hasWindowFocus() && position(R.id.categories) == 0 && activity.currentFocus != null
            } }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertPosition(R.id.list, 0)
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            assertPosition(R.id.categories, 0)
        } finally {
            if (opened) main { activity.finishAndRemoveTask() }
            instrumentation.waitForIdleSync()
            check(WatchNextManager.isAvailable(app)) { "Discovery did not restore supported capability" }
        }
    }

    private fun verifyRowsAndBottom() {
        val hiddenLabels = main {
            listOf(R.string.settings_watch_next, R.string.settings_watch_next_refresh, R.string.settings_watch_next_clear)
                .map(activity::getString)
        }
        val last = main { activity.findViewById<RecyclerView>(R.id.list).adapter!!.itemCount - 1 }
        for (index in 0..last) {
            assertPosition(R.id.list, index)
            check(main {
                val row = activity.findViewById<RecyclerView>(R.id.list).findViewHolderForAdapterPosition(index)!!
                row.itemView.findViewById<TextView>(R.id.label).text.toString() !in hiddenLabels
            }) { "Unavailable Watch Next row was displayed" }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        assertPosition(R.id.list, last)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertPosition(R.id.categories, 0)
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertPosition(R.id.categories, 0)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertPosition(R.id.list, last)
        key(KeyEvent.KEYCODE_BACK)
        assertPosition(R.id.categories, 0)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        repeat(last) { key(KeyEvent.KEYCODE_DPAD_UP) }
        assertPosition(R.id.list, 0)
    }

    private fun resetSupport(context: Context) {
        val field = WatchNextSupport::class.java.getDeclaredField("states").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val states = field.get(WatchNextSupport) as MutableMap<String, *>
        states.remove(context.filesDir.absolutePath)
    }

    private fun position(id: Int): Int {
        val focus = activity.currentFocus ?: return -1
        return activity.findViewById<RecyclerView>(id).findContainingViewHolder(focus)?.bindingAdapterPosition ?: -1
    }

    private fun assertPosition(id: Int, expected: Int) = await { main { position(id) == expected } }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        SystemClock.sleep(120)
        instrumentation.waitForIdleSync()
        await { main {
            listOf(R.id.list, R.id.categories).all {
                val list = activity.findViewById<RecyclerView>(it)
                list.scrollState == RecyclerView.SCROLL_STATE_IDLE && !list.isComputingLayout && !list.isLayoutRequested
            }
        } }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 6_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Unavailable settings navigation timed out" }
            SystemClock.sleep(30)
        }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}

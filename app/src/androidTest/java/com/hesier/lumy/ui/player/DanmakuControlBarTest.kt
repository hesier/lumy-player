@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hesier.lumy.ui.player

import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.core.*
import com.hesier.lumy.data.DanmakuSettings
import com.hesier.lumy.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/** Tests actual PlayerActivity buttons, using only an owned file and a strict server proxy. */
class DanmakuControlBarTest(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val app get() = LumyApp.instance
    private lateinit var activity: PlayerActivity
    private lateinit var binding: ActivityPlayerBinding
    private lateinit var player: ExoPlayer
    private val unexpected = CopyOnWriteArrayList<String>()
    private val serverField = LumyApp::class.java.getDeclaredField("server").apply { isAccessible = true }

    fun runAll(): String {
        instrumentation.waitForIdleSync()
        await("application session initialized") { main { runCatching { app.currentEntry != null }.getOrDefault(false) } }
        requireSafeTasks()
        val session = checkNotNull(app.currentEntry?.session) { "A saved server identity is required" }
        val originalEntry = app.currentEntry
        val originalServer = main { serverField.get(app) }
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val memory = context.getSharedPreferences("track_memory", Context.MODE_PRIVATE)
        val matches = context.getSharedPreferences("danmaku_matches", Context.MODE_PRIVATE)
        val savedPrefs = snapshot(prefs)
        val savedMemory = snapshot(memory)
        val savedMatches = snapshot(matches)
        val file = File(context.cacheDir, "danmaku-control-bar-fixture.mkv")
        instrumentation.context.assets.open("dual-subtitles/fixture.mkv").use { input -> file.outputStream().use { input.copyTo(it) } }
        val proxy = fakeServer(session, file)
        try {
            main { serverField.set(app, proxy); DanmakuSettings(prefs).enabled = false }
            instrumentation.setInTouchMode(false)
            activity = instrumentation.startActivitySync(PlayerActivity.intent(context, FIXTURE_ID, 0, 0)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as PlayerActivity
            main { binding = field("binding") as ActivityPlayerBinding; player = field("player") as ExoPlayer }
            await("local PlayerActivity playback") { main { player.isPlaying && binding.buttons.childCount >= 5 } }
            verifyControlBar()
            return "Real PlayerActivity: Speed/Danmaku/Chapters order, D-pad navigation, debug handoff, menu focus return and More separation passed"
        } finally {
            try {
                if (::activity.isInitialized) {
                    main { activity.finishAndRemoveTask() }
                    await("fixture activity destroyed") { main { activity.isDestroyed } }
                    runBlocking { withContext(Dispatchers.Main) { app.awaitPendingReports() } }
                }
            } finally {
                main { serverField.set(app, originalServer) }
                restore(prefs, savedPrefs); restore(memory, savedMemory)
                check(savedPrefs == prefs.all && savedMemory == memory.all) { "Control-bar preferences were not restored" }
                check(savedMatches == matches.all && originalEntry == app.currentEntry) { "Control-bar test changed stored media/session data" }
            }
        }
    }

    private fun requireSafeTasks() {
        val tasks = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).appTasks
        // Existing players retain their own real server and would report progress when stopped.
        check(tasks.none { it.taskInfo.topActivity?.className == PlayerActivity::class.java.name }) {
            "Close existing playback before running the isolated control-bar test"
        }
    }

    private fun fakeServer(session: ServerSession, file: File): MediaServer {
        val video = VideoStream("h264", 320, 180, 24f, 8, "High", HdrType.NONE, null, 500)
        val version = MediaVersion("control-bar-version", 0, "mkv", 600, video, emptyList(), emptyList(),
            listOf(MediaPart("control-bar-part", "fixture", "mkv", file.length(), 20_000)))
        val item = MediaItem(FIXTURE_ID, MediaType.MOVIE, "Control fixture", durationMs = 20_000)
        val detail = MediaDetail(item, versions = listOf(version), chapters = listOf(
            Chapter(0, "First", 0, 5_000, null), Chapter(1, "Second", 5_000, 20_000, null)))
        return Proxy.newProxyInstance(MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java)) { proxy, method, args ->
            when (method.name) {
                "getSession" -> session
                "getEmbedsSubtitles", "getSupportsFavorites" -> false
                "detail" -> { check(args?.firstOrNull() == FIXTURE_ID); detail }
                "playbackSource" -> {
                    val request = args?.firstOrNull() as PlaybackRequest
                    check(request.item.id == FIXTURE_ID)
                    PlaybackSource(listOf(file.toURI().toString()), false, emptyList(), 0, request.startMs)
                }
                "playbackHeaders" -> emptyMap<String, String>()
                "reportProgress", "stopPlayback", "saveStreamSelection" -> Unit
                "danmakuResource", "nextEpisode" -> null
                "homeHubs", "children", "containerItems", "libraries", "similar" -> emptyList<Any>()
                "isReachable" -> true
                "toString" -> "ControlBarFixtureServer"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> { unexpected.add(method.name); error("Unexpected isolated server method: ${method.name}") }
            }
        } as MediaServer
    }

    private fun verifyControlBar() {
        await("PlayerActivity window focused") { main { activity.hasWindowFocus() } }
        if (main { binding.controls.visibility == View.VISIBLE }) key(KeyEvent.KEYCODE_BACK)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("paused controls focused") { main { !player.isPlaying && binding.buttons.getChildAt(0).hasFocus() } }
        val indices = main {
            val labels = (0 until binding.buttons.childCount).map { label(binding.buttons.getChildAt(it)) }
            val speed = labels.indexOf(activity.getString(R.string.player_speed))
            val danmaku = labels.indexOf(activity.getString(R.string.settings_section_danmaku))
            val chapters = labels.indexOf(activity.getString(R.string.player_chapters))
            check(speed >= 0 && danmaku == speed + 1 && chapters == danmaku + 1)
            listOf(speed, danmaku, chapters, labels.indexOf(activity.getString(R.string.player_more)))
        }
        repeat(indices[0]) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        focus(indices[0]); key(KeyEvent.KEYCODE_DPAD_RIGHT); focus(indices[1])
        key(KeyEvent.KEYCODE_DPAD_RIGHT); focus(indices[2])
        key(KeyEvent.KEYCODE_DPAD_LEFT); focus(indices[1])
        checkDanmakuMenu(indices[1])
        key(KeyEvent.KEYCODE_DPAD_RIGHT); focus(indices[2])
        key(KeyEvent.KEYCODE_DPAD_RIGHT); focus(indices[3])
        checkMoreMenu(indices[3])
        key(KeyEvent.KEYCODE_DPAD_LEFT); focus(indices[2])
        key(KeyEvent.KEYCODE_DPAD_LEFT); focus(indices[1])
    }

    private fun checkDanmakuMenu(index: Int) {
        val probe = main { field("danmakuProbe") as DanmakuProbe }
        val controller = main { field("danmaku") as PlayerDanmaku }
        main { probe.setProfile(2); controller.setTestActive(true) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPanel(R.string.danmaku_enabled)
        check(!screenTexts().contains(main { activity.getString(R.string.danmaku_switch_source) }))
        main {
            check(probe.profile == 0)
            val testing = PlayerDanmaku::class.java.getDeclaredField("testing").apply { isAccessible = true }
            check(testing.get(controller) == false) { "Debug generator still blocks the formal controller" }
        }
        key(KeyEvent.KEYCODE_BACK)
        focus(index)
    }

    private fun checkMoreMenu(index: Int) {
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPanel(R.string.player_more)
        val texts = screenTexts()
        main {
            listOf(R.string.settings_section_danmaku, R.string.danmaku_manual_match,
                R.string.danmaku_reload, R.string.danmaku_restore_auto).forEach { check(activity.getString(it) !in texts) }
        }
        key(KeyEvent.KEYCODE_BACK)
        focus(index)
    }

    private fun awaitPanel(label: Int) = await("real controller panel") {
        main { !activity.hasWindowFocus() } && screenTexts().contains(main { activity.getString(label) })
    }
    private fun focus(index: Int) = await("control focus returned") { main {
        activity.hasWindowFocus() && index >= 0 && binding.buttons.getChildAt(index).hasFocus()
    } }
    private fun label(view: View): String = view.findViewById<TextView>(R.id.label)?.text?.toString().orEmpty()
    private fun field(name: String): Any? = PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); Thread.sleep(60) }

    private fun screenTexts(): List<String> {
        fun collect(node: AccessibilityNodeInfo?): List<String> {
            if (node == null) return emptyList()
            val result = mutableListOf<String>()
            node.text?.toString()?.let(result::add)
            for (index in 0 until node.childCount) result.addAll(collect(node.getChild(index)))
            node.recycle()
            return result
        }
        return collect(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < deadline) {
            main {
                check(unexpected.isEmpty()) { "Unexpected isolated server calls" }
                if (::player.isInitialized && !activity.isFinishing) player.playerError?.let { error("Local fixture player error: ${it.errorCodeName}") }
            }
            if (condition()) return
            Thread.sleep(50)
        }
        error("Timed out: $label")
    }
    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    private fun snapshot(prefs: SharedPreferences): Map<String, *> = prefs.all.mapValues { (_, value) ->
        if (value is Set<*>) value.toSet() else value
    }
    private fun restore(prefs: SharedPreferences, saved: Map<String, *>) {
        val editor = prefs.edit().clear()
        saved.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        check(editor.commit())
    }
    private companion object { const val FIXTURE_ID = "codex-danmaku-control-bar-fixture" }
}

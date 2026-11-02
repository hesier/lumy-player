@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.MediaItem
import com.hesier.lumy.R
import com.hesier.lumy.core.MediaDetail
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.MediaPart
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import com.hesier.lumy.data.*
import com.hesier.lumy.databinding.ActivityPlayerBinding
import com.hesier.lumy.ui.settings.SettingsActivity
import com.hesier.lumy.ui.widget.addActionButton
import okhttp3.OkHttpClient
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

internal class DanmakuPlayerFlowFixture(private val instrumentation: Instrumentation, val live: Boolean) : AutoCloseable {
    val context = instrumentation.targetContext
    val preferences = context.getSharedPreferences("danmaku-player-flow-test", Context.MODE_PRIVATE)
    val store = DanmakuStore(context, "danmaku-player-flow-test")
    private val global = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val originalGlobal = global.all.toMap()
    lateinit var activity: SettingsActivity
        private set
    private lateinit var binding: ActivityPlayerBinding
    private lateinit var bundle: PlayerBundle
    lateinit var controller: PlayerDanmaku
        private set
    private lateinit var actionButton: View
    private var clockMs = 2_000L
    private var menuClicks = 0
    private var pluginEndpoint: String? = null
    val toasts = CopyOnWriteArrayList<String>()
    private val resultField = PlayerDanmaku::class.java.getDeclaredField("result").apply { isAccessible = true }
    private val mediaField = PlayerDanmaku::class.java.getDeclaredField("media").apply { isAccessible = true }
    private val overlayField = PlayerDanmaku::class.java.getDeclaredField("overlay").apply { isAccessible = true }

    fun open(endpoint: String?, withPlugin: Boolean = false) {
        pluginEndpoint = endpoint.takeIf { withPlugin }
        check(preferences.edit().clear().commit())
        store.clearMatches(); store.clearCache()
        val source = DanmakuSourceSettings(preferences)
        if (live) {
            val current = DanmakuSourceSettings(global)
            source.onlineSource = current.onlineSource
            source.customUrl = current.customUrl
        } else {
            source.onlineSource = DanmakuOnlineSource.CUSTOM
            source.customUrl = checkNotNull(endpoint)
        }
        DanmakuSettings(preferences).enabled = true
        instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                event.packageName?.toString() == context.packageName) toasts.addAll(event.text.map(CharSequence::toString))
        }
        activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as SettingsActivity
        val file = File(context.cacheDir, "danmaku-player-flow-fixture.mkv")
        instrumentation.context.assets.open("dual-subtitles/fixture.mkv").use { input -> file.outputStream().use { input.copyTo(it) } }
        main { setup(file) }
        await("local video playing") { main { bundle.player.isPlaying && bundle.player.currentPosition > 500 } }
        main { bundle.player.pause(); bind() }
    }

    private fun setup(file: File) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        binding = ActivityPlayerBinding.inflate(activity.layoutInflater)
        activity.setContentView(binding.root)
        binding.controls.visibility = View.GONE; binding.buffering.visibility = View.GONE
        bundle = PlayerFactory.create(activity, OkHttpClient(), emptyMap(), SyncOffsets())
        binding.playerView.player = bundle.player
        controller = PlayerDanmaku(activity, binding.playerView, bundle.player, positionMs = { clockMs },
            preferences = preferences, store = store)
        actionButton = binding.root.addActionButton(R.drawable.ic_danmaku, activity.getString(R.string.settings_section_danmaku)) {
            menuClicks++
            controller.showMenu()
        }
        actionButton.requestFocus()
        bundle.player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
        bundle.player.prepare(); bundle.player.play()
    }

    private fun bind() {
        val server = Proxy.newProxyInstance(MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java)) { _, method, _ ->
            when (method.name) {
                "getSession" -> ServerSession(ServerKind.PLEX, "flow", "fixture", "http://127.0.0.1", "")
                "danmakuResource" -> pluginEndpoint?.let {
                    com.hesier.lumy.core.ServerDanmaku("$it/plugin", emptyMap(), com.hesier.lumy.core.ServerDanmakuFormat.XML)
                }
                else -> error("Unexpected server call: ${method.name}")
            }
        } as MediaServer
        val item = com.hesier.lumy.core.MediaItem("flow", MediaType.EPISODE, "Episode", showTitle = if (live) "三体" else "Flow Show",
            seasonNumber = 1, episodeNumber = if (live) 1 else 2, year = 2023)
        val version = MediaVersion("local", 0, "mkv", null, null, emptyList(), emptyList(),
            listOf(MediaPart("part", "local", "mkv", null, 60_000)))
        controller.bind(server, MediaDetail(item, versions = listOf(version)), version, "flow-only")
    }

    fun reopenController() = main {
        controller.release()
        controller = PlayerDanmaku(activity, binding.playerView, bundle.player, positionMs = { clockMs },
            preferences = context.getSharedPreferences("danmaku-player-flow-test", Context.MODE_PRIVATE),
            store = DanmakuStore(context, "danmaku-player-flow-test"))
        bind()
    }

    fun result(): DanmakuLoad? = main { resultField.get(controller) as? DanmakuLoad }
    fun mediaKey(): String = main { (mediaField.get(controller) as DanmakuMedia).key }
    fun loaded(): DanmakuLoad.Loaded = checkNotNull(result() as? DanmakuLoad.Loaded)
    fun awaitLoaded(automatic: Boolean): DanmakuLoad.Loaded {
        await("controller loaded") { (result() as? DanmakuLoad.Loaded)?.automatic == automatic }
        return loaded()
    }

    fun checkVisible() {
        val loaded = loaded()
        val target = loaded.comments.firstOrNull { it.mode == 1 } ?: loaded.comments.first()
        main { clockMs = target.timeMs + loaded.sourceOffsetMs + 2_000
            bundle.player.seekTo(if (bundle.player.currentPosition == 2_000L) 3_000 else 2_000) }
        var previous = -1L
        var changed = SystemClock.elapsedRealtime()
        await("formal overlay settled and visible") { main {
            val comments = overlayField.get(controller) as DanmakuOverlay
            val updates = comments.metrics.update.count
            val now = SystemClock.elapsedRealtime()
            if (updates != previous) { previous = updates; changed = now }
            now - changed >= 500 && comments.metrics.draw.count > 0 && comments.visibleCount > 0 &&
                kotlin.math.abs(comments.currentTimeMs - (clockMs - loaded.sourceOffsetMs)) < 100
        } }
    }

    fun openMenu() {
        await("focused player window") { main { activity.hasWindowFocus() && actionButton.isShown } }
        instrumentation.setInTouchMode(false)
        await("focused danmaku action") { main { actionButton.requestFocus() && activity.currentFocus === actionButton } }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("danmaku menu") { screenTexts().contains(text(R.string.danmaku_manual_match)) }
        check(!screenTexts().contains(text(R.string.danmaku_switch_source))) { "Removed source action remains visible" }
    }

    fun pick(label: String) {
        await("option visible") { screenTexts().contains(label) }
        repeat(15) {
            if (focusedTexts().contains(label)) { key(KeyEvent.KEYCODE_DPAD_CENTER); return }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        error("Expected option did not receive remote focus")
    }

    fun submitSearch() {
        await("search input focus") {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val node = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            root?.recycle()
            val edit = node?.className?.toString()?.contains("EditText") == true
            node?.recycle(); edit
        }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("search submit focus") { focusedTexts().contains(text(R.string.danmaku_search)) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    fun pickFirstAnime(): String {
        await("anime results focused") {
            screenTexts().contains(text(R.string.danmaku_search)) && focusedTexts().isNotEmpty() &&
                !focusedTexts().contains(text(R.string.danmaku_search))
        }
        val label = focusedTexts().first()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        return label
    }

    fun pickEpisode(workTitle: String, index: Int) {
        await("episode results focused") {
            screenTexts().firstOrNull() == workTitle && focusedTexts().any { it != workTitle }
        }
        repeat(index) {
            val before = focusedTexts()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("next episode focused") { focusedTexts().isNotEmpty() && focusedTexts() != before }
        }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    fun feedbackPrefix(id: Int): String = main { activity.getString(id, "") }

    fun text(id: Int) = main { activity.getString(id) }
    fun expectToast(text: String) = await("expected feedback toast") { toasts.any { it.contains(text) } }
    fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); Thread.sleep(60) }
    fun screenTexts(): List<String> = nodeTexts(instrumentation.uiAutomation.rootInActiveWindow)
    private fun focusedTexts(): List<String> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        root.recycle()
        return nodeTexts(focus)
    }
    private fun nodeTexts(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        val result = mutableListOf<String>()
        node.text?.toString()?.let(result::add)
        for (index in 0 until node.childCount) result.addAll(nodeTexts(node.getChild(index)))
        node.recycle()
        return result
    }

    fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        val state = main { "clicks=$menuClicks focused=${activity.currentFocus === actionButton}" +
            " window=${activity.hasWindowFocus()} touch=${actionButton.isInTouchMode}" +
            " shown=${actionButton.isShown} enabled=${DanmakuSettings(preferences).enabled}" }
        error("Timed out: $label ($state)")
    }
    fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    override fun close() {
        instrumentation.uiAutomation.setOnAccessibilityEventListener(null)
        if (::activity.isInitialized) main {
            if (::controller.isInitialized) controller.release()
            if (::bundle.isInitialized) {
                binding.playerView.player = null
                bundle.player.release(); bundle.ass.release(); bundle.secondaryAss.release()
            }
            activity.finishAndRemoveTask()
        }
        store.clearCache(); store.clearMatches(); check(preferences.edit().clear().commit())
        check(originalGlobal == global.all) { "Flow test changed global preferences" }
    }
}

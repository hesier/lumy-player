@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.media3.ui.SubtitleView
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.databinding.ActivityPlayerBinding
import kotlin.math.abs

/** Uses the caller's isolated local player and preference snapshot. */
internal class PlaybackStyleControlsTest(
    private val instrumentation: Instrumentation,
    private val activity: PlayerActivity,
    private val binding: ActivityPlayerBinding,
) {
    private val settings get() = LumyApp.instance.settings

    fun checkDanmakuStyle() {
        main { settings.danmaku.opacityPercent = 80 }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocus(R.string.player_danmaku_settings)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitTitle(R.string.player_danmaku_settings)
        awaitFocus(R.string.danmaku_density)
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitFocus(R.string.danmaku_opacity)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitTitle(R.string.danmaku_opacity)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("danmaku opacity saved immediately") { main { settings.danmaku.opacityPercent == 90 } }
        awaitTitle(R.string.player_danmaku_settings)
        awaitFocus(R.string.danmaku_opacity)
        key(KeyEvent.KEYCODE_BACK)
        awaitFocus(R.string.player_danmaku_settings)
    }

    fun checkSubtitleStyle() {
        main {
            val title = activity.getString(R.string.player_subtitles)
            val button = (0 until binding.buttons.childCount).map { binding.buttons.getChildAt(it) }
                .first { it.findViewById<TextView>(R.id.label)?.text == title }
            check(button.requestFocus())
        }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitTitle(R.string.player_subtitles)
        awaitFocus("Fixture SRT")
        key(KeyEvent.KEYCODE_DPAD_UP)
        awaitFocus(R.string.subtitles_off)
        key(KeyEvent.KEYCODE_DPAD_UP)
        awaitFocus(R.string.player_subtitle_style)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitTitle(R.string.player_subtitle_style)
        awaitFocus(R.string.settings_subtitle_size)
        chooseLargerSubtitle()
        key(KeyEvent.KEYCODE_BACK)
        awaitTitle(R.string.player_subtitles)
        awaitFocus(R.string.player_subtitle_style)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocus(R.string.subtitles_off)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocus("Fixture SRT")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("track selection returns to player") { main { activity.hasWindowFocus() } }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitTitle(R.string.player_subtitles)
        awaitFocus("Fixture SRT")
        key(KeyEvent.KEYCODE_BACK)
        await("subtitle panel closes") { main { activity.hasWindowFocus() } }
    }

    private fun chooseLargerSubtitle() {
        val before = subtitleTextSize()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitTitle(R.string.settings_subtitle_size)
        awaitFocus(R.string.subtitle_size_normal)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocus(R.string.subtitle_size_large)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("subtitle preference and active SubtitleView update immediately") {
            main { settings.subtitleScale == 1.25f } && subtitleTextSize() > before &&
                abs(subtitleTextSize() - SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.1f * 1.25f) < 0.00001f
        }
        awaitTitle(R.string.player_subtitle_style)
        awaitFocus(R.string.settings_subtitle_size)
    }

    private fun subtitleTextSize(): Float = main {
        val view = checkNotNull(binding.playerView.subtitleView)
        SubtitleView::class.java.getDeclaredField("defaultTextSize").apply { isAccessible = true }.getFloat(view)
    }

    private fun awaitFocus(resource: Int) = awaitFocus(main { activity.getString(resource) })

    private fun awaitFocus(label: String) = await("focus on $label") {
        val root = instrumentation.uiAutomation.rootInActiveWindow
        val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val labels = texts(focused)
        root?.recycle()
        label in labels
    }

    private fun awaitTitle(resource: Int) = await("style panel title") {
        val title = main { activity.getString(resource) }
        !main { activity.hasWindowFocus() } && texts(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull() == title
    }

    private fun texts(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        val result = mutableListOf<String>()
        node.text?.toString()?.let(result::add)
        for (index in 0 until node.childCount) result.addAll(texts(node.getChild(index)))
        node.recycle()
        return result
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
        Thread.sleep(60)
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < deadline) {
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
}

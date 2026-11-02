package com.hesier.lumy.ui.settings

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.data.DanmakuDensity
import com.hesier.lumy.data.DanmakuSettings
import com.hesier.lumy.data.DanmakuSize
import com.hesier.lumy.data.DanmakuSpeed
import com.hesier.lumy.data.DanmakuStroke
import java.io.File

/** 使用真实方向键操作设置页；退出时还原用户原有的弹幕偏好。 */
class DanmakuSettingsUiTest(private val instrumentation: Instrumentation) {
    private lateinit var activity: SettingsActivity
    private val settings get() = LumyApp.instance.settings.danmaku

    fun runAll() {
        val prefs = instrumentation.targetContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val saved = prefs.all.filterKeys { it in DanmakuSettings.KEYS }
        val originalSeek = LumyApp.instance.settings.seekForwardSeconds
        main { settings.reset() }
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as SettingsActivity
        try {
            await { main { activity.hasWindowFocus() } }
            focus(R.string.settings_section_danmaku, KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            focus(R.string.danmaku_enabled, KeyEvent.KEYCODE_DPAD_UP)
            key(KeyEvent.KEYCODE_DPAD_UP)
            assertFocus(R.string.danmaku_enabled)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(settings.enabled) }
            checkChoices()
            checkTypes()
            checkOffset()
            checkRecreateAndNavigation()
            checkReset(originalSeek)
            screenshot()
        } finally {
            main {
                activity.finishAndRemoveTask()
                val editor = prefs.edit()
                DanmakuSettings.KEYS.forEach(editor::remove)
                saved.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is String -> editor.putString(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                    }
                }
                check(editor.commit())
            }
        }
    }

    private fun checkChoices() {
        pick(R.string.danmaku_density, 2)
        main { check(settings.density == DanmakuDensity.HIGH) }
        pick(R.string.danmaku_area, 2)
        main { check(settings.areaPercent == 100) }
        pick(R.string.danmaku_size, 2)
        main { check(settings.size == DanmakuSize.EXTRA_LARGE) }
        pick(R.string.danmaku_size, -4)
        main { check(settings.size == DanmakuSize.EXTRA_SMALL) }
        pick(R.string.danmaku_opacity, 2)
        main { check(settings.opacityPercent == 100) }
        pick(R.string.danmaku_speed, -1)
        main { check(settings.speed == DanmakuSpeed.SLOW) }
        pick(R.string.danmaku_stroke, 2)
        main { check(settings.stroke == DanmakuStroke.THICK) }
        focus(R.string.danmaku_auto_reduce, KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        main { check(!settings.autoReduce && settings.density == DanmakuDensity.HIGH) }
    }

    private fun checkTypes() {
        focus(R.string.danmaku_types, KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await { main { !activity.hasWindowFocus() } }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.KEYCODE_BACK)
        assertFocus(R.string.danmaku_types)
        main { check(!settings.rolling && !settings.top && settings.bottom) }
    }

    private fun checkOffset() {
        focus(R.string.danmaku_offset, KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        main { check(settings.offsetMs == 500L) }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        main { check(settings.offsetMs == -500L) }
        key(KeyEvent.KEYCODE_BACK)
        assertFocus(R.string.danmaku_offset)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        main { check(settings.offsetMs == 0L) }
        assertFocus(R.string.danmaku_offset)
    }

    private fun checkRecreateAndNavigation() {
        val monitor = instrumentation.addMonitor(SettingsActivity::class.java.name, null, false)
        try {
            main { activity.recreate() }
            activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000)) as SettingsActivity
        } finally { instrumentation.removeMonitor(monitor) }
        await { main { activity.hasWindowFocus() } }
        assertFocus(R.string.danmaku_offset)
        main { check(settings.density == DanmakuDensity.HIGH) }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertFocus(R.string.settings_section_danmaku)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocus(R.string.danmaku_offset)
        focus(R.string.danmaku_restore_defaults, KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFocus(R.string.danmaku_restore_defaults)
        key(KeyEvent.KEYCODE_BACK)
        assertFocus(R.string.settings_section_danmaku)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocus(R.string.danmaku_restore_defaults)
    }

    private fun checkReset(originalSeek: Int) {
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.KEYCODE_BACK)
        main { check(settings.enabled && settings.density == DanmakuDensity.HIGH) }
        assertFocus(R.string.danmaku_restore_defaults)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        main {
            check(!settings.enabled && settings.density == DanmakuDensity.LOW)
            check(settings.areaPercent == 50 && settings.opacityPercent == 80)
            check(settings.size == DanmakuSize.NORMAL && settings.speed == DanmakuSpeed.NORMAL)
            check(settings.stroke == DanmakuStroke.THIN && settings.autoReduce)
            check(settings.rolling && settings.top && !settings.bottom && settings.offsetMs == 0L)
            check(LumyApp.instance.settings.seekForwardSeconds == originalSeek)
        }
        assertFocus(R.string.danmaku_restore_defaults)
    }

    private fun pick(label: Int, delta: Int) {
        focus(label, KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await { main { !activity.hasWindowFocus() } }
        repeat(kotlin.math.abs(delta)) { key(if (delta > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await { main { activity.hasWindowFocus() } }
        assertFocus(label)
    }

    private fun focus(label: Int, direction: Int) {
        repeat(24) {
            if (main { containsText(activity.currentFocus, activity.getString(label)) }) return
            key(direction)
        }
        screenshot()
        error("Focus did not reach ${activity.getString(label)}; state=" + main {
            "focus=${activity.currentFocus?.findViewById<TextView>(R.id.label)?.text} " +
                "category=${activity.findViewById<View>(R.id.categories).hasFocus()} " +
                "list=${activity.findViewById<View>(R.id.list).hasFocus()} window=${activity.hasWindowFocus()}"
        })
    }

    private fun assertFocus(label: Int) = await { main { containsText(activity.currentFocus, activity.getString(label)) } }

    private fun containsText(view: View?, text: String): Boolean = when (view) {
        is TextView -> view.text.toString() == text
        is ViewGroup -> (0 until view.childCount).any { containsText(view.getChildAt(it), text) }
        else -> false
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        SystemClock.sleep(160)
        instrumentation.waitForIdleSync()
        // 等待列表的滚动和行更新动画完成，再发送下一次方向键。
        await { main {
            val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list)
            !list.isAnimating && list.scrollState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE
        } }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 6_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for danmaku settings UI" }
            SystemClock.sleep(40)
        }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun screenshot() {
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, "danmaku-settings.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }
}

package com.hesier.lumy.ui.settings

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.EditText
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.data.DanmakuOnlineSource
import com.hesier.lumy.data.DanmakuSettings
import com.hesier.lumy.data.DanmakuSourceSettings
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

/** 真实遥控器路径；仅操作来源和显示偏好，结束时逐项还原。 */
class DanmakuSourcesUiTest(private val instrumentation: Instrumentation) {
    private lateinit var activity: SettingsActivity
    private val sources get() = LumyApp.instance.settings.danmakuSource
    private val style get() = LumyApp.instance.settings.danmaku
    private val keys = DanmakuSettings.KEYS + DanmakuSourceSettings.KEYS

    fun runAll() {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val saved = prefs.all.filterKeys { it in keys }
        // 分配后关闭自己的回环端口，确保连接失败不访问用户服务器。
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val address = "http://127.0.0.1:$port/proxy/ui-test-token-path"
        main {
            sources.customUrl = ""
            sources.onlineSource = DanmakuOnlineSource.OFFICIAL
            style.enabled = false
        }
        try {
            activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as SettingsActivity
            await { main { activity.hasWindowFocus() } }
            focus(R.string.settings_section_danmaku, KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            checkNewRowsReachable()
            saveAddress(address)
            chooseCustom()
            checkSummary(port)
            recreate()
            main {
                check(DanmakuOnlineSource.CUSTOM == sources.onlineSource)
                check(address == sources.customUrl)
            }
            checkSummary(port)
            checkDisplayResetKeepsSource(address)
            checkClearMatchesCancel(context)
            checkConnectionFailure()
        } finally {
            instrumentation.uiAutomation.setOnAccessibilityEventListener(null)
            main {
                if (::activity.isInitialized) activity.finishAndRemoveTask()
                restore(prefs, saved)
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun checkNewRowsReachable() {
        for (label in listOf(R.string.danmaku_source_title, R.string.danmaku_custom_url,
            R.string.danmaku_test_api, R.string.danmaku_clear_cache, R.string.danmaku_clear_matches)) {
            focus(label, KeyEvent.KEYCODE_DPAD_DOWN)
            assertFocus(label)
        }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFocus(R.string.danmaku_clear_matches)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertFocus(R.string.settings_section_danmaku)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocus(R.string.danmaku_clear_matches)
    }

    private fun saveAddress(address: String) {
        focus(R.string.danmaku_custom_url, KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await { main { !activity.hasWindowFocus() } }
        await { main { panelFocus()?.id == R.id.input } }
        main {
            val input = panelFocus() as EditText
            input.setText("  $address  ")
            input.setSelection(input.text.length)
            val ime = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            ime.hideSoftInputFromWindow(input.windowToken, 0)
        }
        await { main {
            ViewCompat.getRootWindowInsets(checkNotNull(panelFocus()))
                ?.isVisible(WindowInsetsCompat.Type.ime()) != true
        } }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        check(main { panelFocus()?.id == R.id.save }) {
            "DOWN did not reach Save; " + panelDiagnostic()
        }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        try { await { main { activity.hasWindowFocus() && sources.customUrl == address } } }
        catch (error: IllegalStateException) {
            error("URL save failed; " + panelDiagnostic() + main { " stored=${sources.customUrl}" })
        }
        assertFocus(R.string.danmaku_custom_url)
    }

    private fun chooseCustom() {
        focus(R.string.danmaku_source_title, KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await { main { !activity.hasWindowFocus() } }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await { main { activity.hasWindowFocus() && sources.onlineSource == DanmakuOnlineSource.CUSTOM } }
        assertFocus(R.string.danmaku_source_title)
    }

    private fun checkSummary(port: Int) {
        focus(R.string.danmaku_custom_url, KeyEvent.KEYCODE_DPAD_DOWN)
        main {
            val row = checkNotNull(activity.currentFocus)
            check(containsText(row, activity.getString(R.string.danmaku_source_configured, "127.0.0.1:$port")))
            check(!anyText(activity.window.decorView) { it.contains("ui-test-token-path") || it.contains("/proxy/") }) {
                "Settings summary exposed the configured token path"
            }
        }
    }

    private fun recreate() {
        val monitor = instrumentation.addMonitor(SettingsActivity::class.java.name, null, false)
        try {
            main { activity.recreate() }
            activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000)) as SettingsActivity
        } finally { instrumentation.removeMonitor(monitor) }
        await { main { activity.hasWindowFocus() } }
        assertFocus(R.string.danmaku_custom_url)
    }

    private fun checkDisplayResetKeepsSource(address: String) {
        focus(R.string.danmaku_restore_defaults, KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await { main { !activity.hasWindowFocus() } }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await { main { activity.hasWindowFocus() } }
        main {
            check(!style.enabled)
            check(DanmakuOnlineSource.CUSTOM == sources.onlineSource)
            check(address == sources.customUrl)
        }
        assertFocus(R.string.danmaku_restore_defaults)
    }

    private fun checkClearMatchesCancel(context: Context) {
        val matches = context.getSharedPreferences("danmaku_matches", Context.MODE_PRIVATE)
        val before = matches.all.toMap()
        focus(R.string.danmaku_clear_matches, KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await { main { !activity.hasWindowFocus() } }
        // 确认面板初始焦点应落在取消，按 OK 后不触碰匹配记录。
        val cancel = main { activity.getString(R.string.cancel) }
        await { main { containsText(panelFocus(), cancel) } }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await { main { activity.hasWindowFocus() } }
        check(before == matches.all) { "Cancel changed saved danmaku matches" }
        assertFocus(R.string.danmaku_clear_matches)
    }

    private fun checkConnectionFailure() {
        val observed = AtomicBoolean(false)
        val expected = main { activity.getString(R.string.danmaku_error_connection) }
        instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                event.text.any { it.toString() == expected }) observed.set(true)
        }
        focus(R.string.danmaku_test_api, KeyEvent.KEYCODE_DPAD_UP)
        main { check(!style.enabled) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await { observed.get() }
        assertFocus(R.string.danmaku_test_api)
        instrumentation.uiAutomation.setOnAccessibilityEventListener(null)
    }

    private fun restore(prefs: SharedPreferences, saved: Map<String, *>) {
        val editor = prefs.edit()
        keys.forEach(editor::remove)
        saved.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
            }
        }
        check(editor.commit())
    }

    private fun focus(label: Int, direction: Int) {
        repeat(24) {
            if (main { containsText(activity.currentFocus, activity.getString(label)) }) return
            key(direction)
        }
        error("D-pad did not reach setting resource $label")
    }

    private fun assertFocus(label: Int) = await {
        main { containsText(activity.currentFocus, activity.getString(label)) }
    }

    private fun panelFocus(): View? = WindowInspector.getGlobalWindowViews()
        .firstOrNull { it.hasWindowFocus() }?.findFocus()

    private fun panelDiagnostic(): String = main {
        val focused = panelFocus()
        "window=${activity.hasWindowFocus()} focusClass=${focused?.javaClass?.simpleName} " +
            "focusId=${focused?.id} input=${(focused as? EditText)?.text}"
    }

    private fun containsText(view: View?, text: String) = anyText(view) { it == text }
    private fun anyText(view: View?, matches: (String) -> Boolean): Boolean = when (view) {
        is TextView -> matches(view.text.toString())
        is ViewGroup -> (0 until view.childCount).any { anyText(view.getChildAt(it), matches) }
        else -> false
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        SystemClock.sleep(160)
        instrumentation.waitForIdleSync()
        await { main {
            val list = activity.findViewById<RecyclerView>(R.id.list)
            !list.isAnimating && list.scrollState == RecyclerView.SCROLL_STATE_IDLE
        } }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for danmaku sources UI" }
            SystemClock.sleep(40)
        }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}

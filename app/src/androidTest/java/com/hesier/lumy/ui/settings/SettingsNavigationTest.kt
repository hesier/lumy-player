package com.hesier.lumy.ui.settings

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.data.AppLanguage

/** Exercises real D-pad movement without clicking or changing any setting rows. */
class SettingsNavigationTest(private val instrumentation: Instrumentation) {
    private lateinit var activity: SettingsActivity

    fun runAll(): String {
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as SettingsActivity
        try {
            await { main { activity.hasWindowFocus() && position(R.id.categories) == 0 } }
            checkEarlyRows()
            checkScrolledRow()
            checkCategoryBounds()
            return "Settings category/row D-pad boundaries and per-category focus memory passed"
        } finally {
            main { activity.finishAndRemoveTask() }
        }
    }

    fun runScaledLanguageMatrix(): String {
        val app = main { LumyApp.instance }
        val (originalLanguage, originalScale) = main { app.settings.language to app.settings.uiScale }
        return try {
            listOf(AppLanguage.ENGLISH, AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.TRADITIONAL_CHINESE).joinToString("\n") { language ->
                main { app.setLanguage(language); app.settings.uiScale = 1.2f }
                "$language scale=1.2: ${runAll()}"
            }
        } finally {
            main { app.setLanguage(originalLanguage); app.settings.uiScale = originalScale }
        }
    }

    private fun checkEarlyRows() {
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertPosition(R.id.list, 0)
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertPosition(R.id.list, 0)
        repeat(2) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertPosition(R.id.list, 2)
        key(KeyEvent.KEYCODE_BACK)
        assertPosition(R.id.categories, 0)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertPosition(R.id.list, 3)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertPosition(R.id.list, 2)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertPosition(R.id.list, 3)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertPosition(R.id.categories, 1)
    }

    private fun checkScrolledRow() {
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertPosition(R.id.categories, 4)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertPosition(R.id.list, 0)
        val last = main { checkNotNull(activity.findViewById<RecyclerView>(R.id.list).adapter).itemCount - 1 }
        repeat(last) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertPosition(R.id.list, last)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertPosition(R.id.list, last)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertPosition(R.id.categories, 4)
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertPosition(R.id.list, last)
        key(KeyEvent.KEYCODE_BACK)
        assertPosition(R.id.categories, 4)
    }

    private fun checkCategoryBounds() {
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertPosition(R.id.categories, 7)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertPosition(R.id.categories, 7)
        repeat(7) { key(KeyEvent.KEYCODE_DPAD_UP) }
        assertPosition(R.id.categories, 0)
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertPosition(R.id.categories, 0)
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        SystemClock.sleep(120)
        instrumentation.waitForIdleSync()
        await { main {
            listOf(R.id.list, R.id.categories).all { id ->
                val list = activity.findViewById<RecyclerView>(id)
                list.scrollState == RecyclerView.SCROLL_STATE_IDLE && !list.isComputingLayout && !list.isLayoutRequested
            }
        } }
    }

    private fun assertPosition(id: Int, expected: Int) = await { main { position(id) == expected } }

    private fun position(id: Int): Int {
        val focus = activity.currentFocus ?: return -1
        return activity.findViewById<RecyclerView>(id).findContainingViewHolder(focus)?.bindingAdapterPosition ?: -1
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 6_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Settings navigation timed out" }
            SystemClock.sleep(30)
        }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}

package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import com.hesier.lumy.databinding.ActivityPlayerBinding

/** Uses the caller's paused local player to check real D-pad focus round trips. */
internal class PlayerButtonFocusTest(
    private val instrumentation: Instrumentation,
    private val binding: ActivityPlayerBinding,
) {
    fun runAll() {
        if (main { binding.controls.visibility == View.VISIBLE }) key(KeyEvent.KEYCODE_BACK)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        val count = main { binding.buttons.childCount }
        check(count > 1) { "Fixture needs multiple playback buttons" }
        for (index in 0 until count) {
            if (index > 0) key(KeyEvent.KEYCODE_DPAD_RIGHT)
            val button = main { binding.buttons.getChildAt(index) }
            main { check(button.hasFocus()) { "Right did not reach button $index" } }
            repeat(3) {
                key(KeyEvent.KEYCODE_DPAD_UP)
                main { check(binding.seek.hasFocus()) { "Up did not reach the seek bar" } }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                main { check(button.hasFocus()) { "Seek bar did not restore button $index" } }
            }
        }
        for (index in count - 2 downTo 0) {
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            main { check(binding.buttons.getChildAt(index).hasFocus()) { "Left skipped button $index" } }
        }
        key(KeyEvent.KEYCODE_BACK)
        main { check(binding.controls.visibility == View.GONE) }
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(60)
    }

    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}

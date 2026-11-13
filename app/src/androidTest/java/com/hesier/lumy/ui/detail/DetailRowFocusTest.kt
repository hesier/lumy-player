package com.hesier.lumy.ui.detail

import android.app.Instrumentation
import android.os.SystemClock
import android.view.KeyEvent
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.databinding.ActivityDetailBinding

/** Checks independent row memories using real D-pad dispatch, including scrolled rows. */
internal class DetailRowFocusTest(
    private val instrumentation: Instrumentation,
    private val detail: DetailActivity,
    private val binding: ActivityDetailBinding,
) {
    fun runAll() {
        await("detail rows ready") { binding.similar.childCount > 0 && binding.fileInfo.childCount > 0 }
        main { check(binding.episodes.getChildAt(0).requestFocus()) }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        checkPosition(binding.cast, 0)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        checkPosition(binding.similar, 0)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        checkPosition(binding.fileInfo, 0)
        key(KeyEvent.KEYCODE_DPAD_UP)
        checkPosition(binding.similar, 0)
        key(KeyEvent.KEYCODE_DPAD_UP)
        checkPosition(binding.cast, 0)

        for (last in listOf(false, true)) {
            val castPosition = if (last) main { binding.cast.adapter!!.itemCount - 1 } else 2
            val similarPosition = if (last) main { binding.similar.adapter!!.itemCount - 1 } else 3
            moveRight(binding.cast, castPosition)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            moveRight(binding.similar, similarPosition)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            moveRight(binding.fileInfo, 1)
            val offsets = main {
                listOf(binding.cast, binding.similar, binding.fileInfo).map { it.computeHorizontalScrollOffset() }
            }
            repeat(3) { checkRoundTrip(castPosition, similarPosition, offsets) }
            key(KeyEvent.KEYCODE_DPAD_UP)
            key(KeyEvent.KEYCODE_DPAD_UP)
        }
    }

    private fun checkRoundTrip(castPosition: Int, similarPosition: Int, offsets: List<Int>) {
        key(KeyEvent.KEYCODE_DPAD_UP)
        checkPosition(binding.similar, similarPosition)
        key(KeyEvent.KEYCODE_DPAD_UP)
        checkPosition(binding.cast, castPosition)
        key(KeyEvent.KEYCODE_DPAD_UP)
        main { check(binding.episodes.hasFocus()) }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        checkPosition(binding.cast, castPosition)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        checkPosition(binding.similar, similarPosition)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        checkPosition(binding.fileInfo, 1)
        main {
            val actual = listOf(binding.cast, binding.similar, binding.fileInfo)
                .map { it.computeHorizontalScrollOffset() }
            check(offsets == actual) { "Row reentry moved horizontal offsets: $offsets -> $actual" }
        }
    }

    private fun moveRight(row: RecyclerView, position: Int) {
        var steps = 0
        while (main { position(row) } < position) {
            check(steps++ < row.adapter!!.itemCount * 3) { "Right navigation stalled" }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        checkPosition(row, position)
    }

    private fun checkPosition(row: RecyclerView, expected: Int) = main {
        check(expected == position(row)) { "Row ${row.id}: expected $expected, actual ${position(row)}" }
    }

    private fun position(row: RecyclerView): Int = detail.currentFocus?.let {
        row.findContainingViewHolder(it)?.bindingAdapterPosition
    } ?: -1

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!main(condition)) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out: $label" }
            SystemClock.sleep(50)
        }
    }

    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}

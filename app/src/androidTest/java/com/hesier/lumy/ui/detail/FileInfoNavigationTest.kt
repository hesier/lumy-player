package com.hesier.lumy.ui.detail

import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import com.hesier.lumy.R
import com.hesier.lumy.core.*
import com.hesier.lumy.databinding.ActivityDetailBinding
import kotlin.math.roundToInt

/** Checks the page footer and dense text cards through real D-pad dispatch. */
internal class FileInfoNavigationTest(
    private val instrumentation: Instrumentation,
    private val detail: DetailActivity,
    private val binding: ActivityDetailBinding,
) {
    fun runAll() {
        main {
            val section = DetailActivity::class.java.getDeclaredField("fileInfo")
                .apply { isAccessible = true }.get(detail) as FileInfoSection
            section.render(version())
        }
        await("file cards laid out") { binding.fileInfo.childCount > 0 && !binding.fileInfo.isLayoutRequested }
        main { check(binding.similar.getChildAt(0).requestFocus()) }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("file row reaches page bottom") { binding.fileInfo.hasFocus() && atBottom() }
        val scrollY = main { binding.scroll.scrollY }
        val count = main { checkNotNull(binding.fileInfo.adapter).itemCount }
        var steps = 0
        while (main { position() } < count - 1) {
            main { checkCard(scrollY) }
            check(steps++ < count * 2) { "Right navigation stalled" }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        main { checkCard(scrollY) }
        repeat(2) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        main { check(position() == count - 1); checkCard(scrollY) }
        repeat(count * 2) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
        main { check(position() == 0); checkCard(scrollY) }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        main { check(position() == 0); checkCard(scrollY) }
        key(KeyEvent.KEYCODE_DPAD_UP)
        main { check(binding.similar.hasFocus()) }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("file row reentry reaches page bottom") { binding.fileInfo.hasFocus() && atBottom() }
        main { checkCard(binding.scroll.scrollY) }
    }

    private fun checkCard(scrollY: Int) {
        check(binding.scroll.scrollY == scrollY) { "Horizontal navigation moved the page vertically" }
        val frame = checkNotNull(detail.currentFocus).findViewById<ViewGroup>(R.id.frame)
        check(frame.scaleX == 1f && frame.scaleY == 1f) { "Text card scaled on focus" }
        check(frame.width == (280 * frame.resources.displayMetrics.density).roundToInt())
        val visible = Rect()
        check(frame.getGlobalVisibleRect(visible) && visible.width() == frame.width && visible.height() == frame.height) {
            "Focused file card is clipped: $visible, size=${frame.width}x${frame.height}"
        }
        val lines = frame.findViewById<ViewGroup>(R.id.lines)
        check(lines.bottom <= frame.height - frame.paddingBottom) { "Dense card text exceeds its content area" }
    }

    private fun version() = MediaVersion(
        "file-info-fixture", 0, "mkv", 60_000,
        VideoStream("hevc", 3840, 2160, 23.976f, 10, "Main 10", HdrType.DOLBY_VISION, 8,
            50_000, "A complete video stream title", "eng"),
        (1..2).map { AudioStream("audio-$it", it, "truehd", 8, "eng", "Audio track $it", it == 1, 6000, 96000) },
        (1..8).map { SubtitleStream("subtitle-$it", it, "srt", "eng", "Subtitle track $it", true, it == 1, "/subtitle-$it") },
        listOf(MediaPart("part", "/fixture.mkv", "mkv", 50_000_000_000L, 7_200_000,
            "/media/A long movie filename with release edition and additional technical metadata.mkv")),
    )

    private fun position(): Int = binding.fileInfo.findContainingViewHolder(checkNotNull(detail.currentFocus))
        ?.bindingAdapterPosition ?: -1

    private fun atBottom(): Boolean = !binding.scroll.canScrollVertically(1)

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 7_000
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

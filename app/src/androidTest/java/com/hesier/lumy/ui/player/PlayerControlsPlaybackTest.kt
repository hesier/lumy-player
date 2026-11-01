@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.hesier.lumy.LumyApp
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.databinding.ActivityPlayerBinding
import com.hesier.lumy.ui.formatClock
import kotlinx.coroutines.runBlocking
import java.io.File

/** Runs real remote-control input against a playing Activity and restores its initial position. */
class PlayerControlsPlaybackTest(private val instrumentation: Instrumentation) {
    private lateinit var activity: PlayerActivity
    private lateinit var player: ExoPlayer
    private lateinit var binding: ActivityPlayerBinding
    private val settings get() = LumyApp.instance.settings
    private var downTime = 0L
    private val results = mutableListOf<String>()

    fun runAll(): String {
        instrumentation.waitForIdleSync()
        val app = LumyApp.instance
        val server = checkNotNull(app.currentServer()) { "A connected media server is required" }
        val item = runBlocking { server.homeHubs() }.flatMap { it.items }.first {
            it.type == MediaType.MOVIE && !it.watched && (it.viewOffsetMs ?: 0) > 0 &&
                (it.durationMs ?: 0) - (it.viewOffsetMs ?: 0) > 120_000
        }
        val start = item.viewOffsetMs ?: 0
        val oldRemaining = settings.showRemainingTime
        val oldHoldSpeed = settings.holdSpeed
        try {
            activity = instrumentation.startActivitySync(PlayerActivity.intent(
                instrumentation.targetContext, item.id, 0, start,
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
            player = field("player") as ExoPlayer
            binding = field("binding") as ActivityPlayerBinding
            await("real video playing", 45_000) { player.isPlaying && player.currentPosition > 0 }
            checkConfirmFocus()
            checkTimesAndScrub()
            checkAutoHide()
            checkTapAndHold()
            checkConfiguredSpeed()
            checkPassthroughGuard()
            checkCancelAndMenu()
            results += "Real video playback, time labels, scrub preview, 3-second hide, hold speeds and cancellation passed"
        } finally {
            settings.showRemainingTime = oldRemaining
            settings.holdSpeed = oldHoldSpeed
            if (::player.isInitialized) restoreAndExit(start)
        }
        return results.joinToString("\n")
    }

    private fun checkConfirmFocus() {
        var visible = false
        main { visible = binding.controls.visibility == View.VISIBLE }
        if (visible) press(KeyEvent.KEYCODE_BACK)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        await("confirm pauses and focuses play button") {
            !player.isPlaying && binding.buttons.getChildAt(0).hasFocus()
        }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        main { check(binding.buttons.getChildAt(1).hasFocus()) }
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        await("button resumes with focus") { player.isPlaying && binding.buttons.getChildAt(0).hasFocus() }
        press(KeyEvent.KEYCODE_DPAD_UP)
        main { check(binding.seek.hasFocus()) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        await("seek pause preserves focus") { !player.isPlaying && binding.seek.hasFocus() }
        press(KeyEvent.KEYCODE_BACK)
        press(KeyEvent.KEYCODE_ENTER)
        await("hidden confirm resumes with focus") { player.isPlaying && binding.buttons.getChildAt(0).hasFocus() }
        press(KeyEvent.KEYCODE_BACK)
        results += "Confirm pause/resume focuses play button; left/right and seek navigation preserve usable focus"
    }

    private fun checkTimesAndScrub() {
        press(KeyEvent.KEYCODE_MEDIA_PAUSE)
        press(KeyEvent.KEYCODE_BACK)
        press(KeyEvent.KEYCODE_DPAD_UP)
        await("paused controls visible") { !player.isPlaying && binding.controls.visibility == View.VISIBLE }
        settings.showRemainingTime = true
        await("remaining time") {
            binding.remaining.text.toString() == "-" + formatClock(value("DurationMs") - value("PositionMs"))
        }
        results += "Remaining duration matches the real playback position"
        settings.showRemainingTime = false
        await("total time") { binding.remaining.text.toString() == formatClock(value("DurationMs")) }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        main {
            check(field("scrubTarget") != null)
            check(binding.remaining.text.toString() == formatClock(value("DurationMs")))
        }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        await("total-mode seek committed") { field("scrubTarget") == null }
        settings.showRemainingTime = true
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        main {
            val target = field("scrubTarget") as Long
            check(binding.remaining.text.toString() == "-" + formatClock(value("DurationMs") - target))
        }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        await("remaining-mode seek committed") { field("scrubTarget") == null }
        results += "Left/right scrub and confirm work in both time modes"
        await("seek stream ready", 30_000) { player.playbackState == androidx.media3.common.Player.STATE_READY }
        var playing = false
        main { playing = player.isPlaying }
        if (playing) press(KeyEvent.KEYCODE_DPAD_CENTER)
        await("paused after seek") { !player.isPlaying }
        Thread.sleep(3_400)
        main { check(binding.controls.visibility == View.VISIBLE) { "Paused controls auto-hid" } }
        results += "Paused controls remain visible past 3 seconds"
    }

    private fun checkAutoHide() {
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        await("resume playback") { player.isPlaying }
        press(KeyEvent.KEYCODE_DPAD_UP)
        val started = SystemClock.elapsedRealtime()
        Thread.sleep(2_300)
        main { check(binding.controls.visibility == View.VISIBLE) { "Controls hid before 3 seconds" } }
        await("3-second auto-hide", 2_000) { binding.controls.visibility == View.GONE }
        val elapsed = SystemClock.elapsedRealtime() - started
        check(elapsed in 2_800..3_800) { "Hide elapsed: $elapsed ms" }
        results += "Controls auto-hide after ${elapsed}ms"
    }

    private fun checkTapAndHold() {
        main { setField("speed", 1.25f); player.setPlaybackSpeed(1.25f) }
        key(KeyEvent.ACTION_DOWN)
        Thread.sleep(200)
        main { check(player.isPlaying) { "Short press paused before key-up" } }
        key(KeyEvent.ACTION_UP)
        await("short press pauses") { !player.isPlaying }
        press(KeyEvent.KEYCODE_MEDIA_PLAY)
        await("playing after short tap") { player.isPlaying }
        press(KeyEvent.KEYCODE_BACK)
        settings.holdSpeed = 2f
        key(KeyEvent.ACTION_DOWN)
        Thread.sleep(300)
        main { check(player.playbackParameters.speed == 1.25f) }
        await("hold enters 2x") { player.playbackParameters.speed == 2f }
        main {
            check(binding.hint.visibility == View.VISIBLE && binding.hint.text.contains("2.0"))
            check(binding.controls.visibility == View.GONE)
        }
        screenshot("lumy-hold-2x.png")
        key(KeyEvent.ACTION_DOWN, repeat = 1)
        key(KeyEvent.ACTION_UP)
        await("release restores 1.25x") {
            player.isPlaying && player.playbackParameters.speed == 1.25f && binding.hint.visibility == View.GONE
        }
        results += "Short tap pauses on release; 500ms hold enters 2x; repeat/release preserves playback and restores 1.25x"
    }

    private fun checkConfiguredSpeed() {
        settings.holdSpeed = 3f
        key(KeyEvent.ACTION_DOWN)
        await("configured 3x hold") { player.playbackParameters.speed == 3f }
        Thread.sleep(3_000)
        var position = 0L
        main {
            check(player.isPlaying && player.playbackParameters.speed == 3f && field("holdSpeedActive") == true)
            position = player.currentPosition
        }
        val start = SystemClock.elapsedRealtime()
        Thread.sleep(3_000)
        main {
            val advanced = player.currentPosition - position
            check(advanced > (SystemClock.elapsedRealtime() - start) * 2.5) { "3x advance: $advanced ms; speed=${player.playbackParameters.speed}; playing=${player.isPlaying}" }
            results += "Configured 3x actually advances video by ${advanced}ms in about three seconds after audio output settles"
        }
        key(KeyEvent.ACTION_UP)
        await("3x release") { player.isPlaying && player.playbackParameters.speed == 1.25f }
    }

    private fun checkPassthroughGuard() {
        main { setField("passthroughEncoding", C.ENCODING_AC3) }
        try {
            key(KeyEvent.ACTION_DOWN)
            Thread.sleep(650)
            main { check(player.playbackParameters.speed == 1.25f && field("holdSpeedActive") != true) }
            key(KeyEvent.ACTION_UP)
            main { check(player.isPlaying && player.playbackParameters.speed == 1.25f) }
        } finally {
            main { setField("passthroughEncoding", null) }
        }
        results += "Passthrough guard tested with injected route state; no speed change or accidental pause"
    }

    private fun checkCancelAndMenu() {
        key(KeyEvent.ACTION_DOWN)
        await("hold before menu") { player.playbackParameters.speed == 3f }
        press(KeyEvent.KEYCODE_MENU)
        await("menu cancels hold") { player.playbackParameters.speed == 1.25f }
        key(KeyEvent.ACTION_UP)
        press(KeyEvent.KEYCODE_BACK)
        await("More dismissed") { activity.hasWindowFocus() }
        main { check(player.isPlaying) }
        press(KeyEvent.KEYCODE_DPAD_UP)
        await("seek focused after menu") { binding.controls.visibility == View.VISIBLE && binding.seek.hasFocus() }
        key(KeyEvent.ACTION_DOWN)
        Thread.sleep(650)
        main { check(!player.isPlaying && player.playbackParameters.speed == 1.25f) }
        key(KeyEvent.ACTION_UP)
        results += "Opening More cancels hold; confirm on visible seek bar keeps normal pause behavior"
    }

    private fun restoreAndExit(position: Long) {
        main {
            activity.javaClass.getDeclaredMethod("seekTo", Long::class.javaPrimitiveType).apply {
                isAccessible = true
            }.invoke(activity, position)
            player.pause()
        }
        await("restore original playback position", 15_000) { kotlin.math.abs(value("PositionMs") - position) < 1_000 }
        main { activity.finish() }
        instrumentation.waitForIdleSync()
        runBlocking { LumyApp.instance.awaitPendingReports() }
    }

    private fun key(action: Int, repeat: Int = 0) {
        if (action == KeyEvent.ACTION_DOWN && repeat == 0) downTime = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(downTime, SystemClock.uptimeMillis(), action,
            KeyEvent.KEYCODE_DPAD_CENTER, repeat))
    }

    private fun press(code: Int) = instrumentation.sendKeyDownUpSync(code)
    private fun field(name: String): Any? = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun setField(name: String, value: Any?) = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    private fun value(name: String) = activity.javaClass.getDeclaredMethod("get$name").apply { isAccessible = true }.invoke(activity) as Long
    private fun main(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        result!!.getOrThrow()
    }

    private fun await(label: String, timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            var passed = false
            main { player.playerError?.let { throw it }; passed = condition() }
            if (passed) return
            Thread.sleep(25)
        }
        var state = ""
        main { state = "state=${player.playbackState} playWhenReady=${player.playWhenReady} suppressed=${player.playbackSuppressionReason}" }
        error("Timed out: $label; $state; passed checks: ${results.joinToString()}")
    }

    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}

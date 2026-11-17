@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.media3.common.Player
import com.hesier.lumy.R
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

internal class PlaybackFeaturesTest(private val instrumentation: Instrumentation) {
    private val fixture = FeaturePlaybackFixture(instrumentation)

    fun runAll(): String {
        checkTimerDeadline()
        checkEarlyExit()
        try {
            fixture.open()
            checkQualityRecovery()
            checkTimerAcrossEpisodes()
            return "Quality recovery and sleep timer checks passed"
        } finally { fixture.close() }
    }

    private fun checkEarlyExit() {
        val early = FeaturePlaybackFixture(instrumentation)
        try {
            early.holdDetail = true
            early.open(waitReady = false)
        } finally { early.close() }
        SystemClock.sleep(150)
        check(early.requests.isEmpty()) { "A late detail started playback after exit" }
    }

    private fun checkQualityRecovery() = with(fixture) {
        main {
            player.pause()
            player.seekTo(4000)
            selection.speed = 1.25f
            player.setPlaybackSpeed(1.25f)
            failLimited = true
            invoke("switchQuality", Quality.Limited(720, 4000))
        }
        await("previous paused quality restored") {
            requests.size == 3 && selection.quality == Quality.Original && player.playbackState == Player.STATE_READY
        }
        main {
            check(!player.playWhenReady && player.playbackParameters.speed == 1.25f)
            check(requests[1].startMs == requests[2].startMs && requests[2].startMs >= 3900)
            failOriginal = true
            invoke("switchQuality", Quality.Limited(720, 4000))
        }
        await("failed recovery exposes retry") { binding.state.root.visibility == View.VISIBLE }
        val count = requests.size
        SystemClock.sleep(500)
        check(count == requests.size) { "Recovery looped after its single attempt" }
        main {
            failOriginal = false
            failLimited = false
            invoke("restartPlayback", false)
        }
        await("original ready after manual restart") { player.playbackState == Player.STATE_READY }
        main { invoke("switchQuality", Quality.Limited(720, 4000)) }
        await("successful quality change") { selection.quality is Quality.Limited && player.playbackState == Player.STATE_READY }
        main {
            check(!player.playWhenReady)
            invoke("switchQuality", Quality.Original)
        }
        await("original quality ready") { selection.quality == Quality.Original && player.playbackState == Player.STATE_READY }
    }

    private fun checkTimerAcrossEpisodes() = with(fixture) {
        main { player.pause() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU)
        SystemClock.sleep(200)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(200)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        await("D-pad selects 15-minute timer") { timer.optionMinutes == 15 }
        main { invoke("switchTo", MediaItem("second", MediaType.EPISODE, "second"), 0L) }
        await("next episode retains deadline") { requests.last().item.id == "second" && player.playbackState == Player.STATE_READY }
        main {
            check(timer.optionMinutes == 15 && timer.remainingMinutes in 14..15)
            timer.set(SleepTimer.END_OF_ITEM)
            invoke("onEnded")
        }
        await("end-of-video timer exits playback") { activity.isDestroyed }
        await("stopped progress recorded") { reports.any { it.first.item.id == "second" && it.second == PlaybackState.STOPPED } }
        check(stopped.contains(requests.last().sessionId))
    }

    private fun checkTimerDeadline() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var clock = 0L
        var expired = 0
        val timer = SleepTimer(scope, { expired++ }, { clock })
        try {
            fixture.main { timer.set(15); check(timer.remainingMinutes == 15); clock = 899_999 }
            fixture.main { check(timer.remainingMinutes == 1); clock = 900_000 }
            fixture.await("wall-clock timer expires") { expired == 1 && timer.optionMinutes == 0 }
            fixture.main { timer.set(30); timer.clear(); clock += 1_800_000 }
            SystemClock.sleep(1100)
            fixture.main { check(expired == 1); timer.set(SleepTimer.END_OF_ITEM); timer.expire(); timer.expire(); check(expired == 2) }
        } finally { scope.cancel() }
    }
}

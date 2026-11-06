@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.exoplayer.ExoPlayer
import com.hesier.lumy.LumyApp
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.PlaybackMode
import com.hesier.lumy.core.PlaybackRequest
import com.hesier.lumy.core.PlaybackSource
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/** Exercises remote input and source requests against the owned local-video fixture. */
internal class TranscodeSkipTest(
    private val instrumentation: Instrumentation,
    private val activity: PlayerActivity,
    private val probe: SkipPlaybackProbe,
) {
    private val player get() = field("player") as ExoPlayer
    private val requests get() = probe.requests

    fun runAll() {
        main {
            player.pause()
            LumyApp.instance.settings.seekForwardSeconds = 5
            LumyApp.instance.settings.seekBackSeconds = 5
        }
        coalescedRestart()
        immediateSeek(PlaybackMode.Direct)
        immediateSeek(PlaybackMode.Transcode())
        cancelOnExplicitSeek()
        reversePendingSkip()
        skipWhileSourcePending()
        cancelOnVersionChange()
    }

    private fun coalescedRestart() {
        val before = requests.size
        main {
            prepareRestart()
            repeat(3) { press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) }
            check(field("scrubTarget") == 16_000L)
            check(requests.size == before) { "Transcode restarted before the burst settled" }
        }
        await("one source request at the final skip target") { requests.size == before + 1 }
        check(requests.last().startMs == 16_000L)
        Thread.sleep(450)
        check(requests.size == before + 1) { "Intermediate transcode sources were requested" }
        main { player.pause() }
        await("local seek ready") { main { player.playbackState == androidx.media3.common.Player.STATE_READY } }
    }

    private fun immediateSeek(mode: PlaybackMode) {
        await("buffered local seek available") {
            main { player.playbackState == androidx.media3.common.Player.STATE_READY && player.bufferedPosition >= 6_000L }
        }
        val before = requests.size
        main {
            player.pause()
            set("plan", Plan(mode, emptyList()))
            set("timeBase", 0L)
            set("pendingSeekMs", 1_000L)
            press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            check(field("scrubTarget") == null) { "Buffered skip was unnecessarily delayed" }
            check(requests.size == before)
            check(player.currentPosition == 6_000L)
        }
    }

    private fun reversePendingSkip() {
        val before = requests.size
        main {
            prepareRestart()
            press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            press(KeyEvent.KEYCODE_MEDIA_REWIND)
            check(field("scrubTarget") == 1_000L) { "Direction change lost the pending target" }
        }
        await("reversed skip source") { requests.size == before + 1 }
        check(requests.last().startMs == 1_000L)
        main { player.pause() }
        await("reversed local seek ready") { main { player.playbackState == androidx.media3.common.Player.STATE_READY } }
    }

    private fun cancelOnExplicitSeek() {
        val before = requests.size
        main {
            set("plan", Plan(PlaybackMode.Transcode(), emptyList()))
            set("timeBase", 10_000L)
            set("pendingSeekMs", 1_000L)
            press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            check(field("scrubTarget") == 6_000L)
            activity.javaClass.getDeclaredMethod("seekTo", Long::class.javaPrimitiveType).apply {
                isAccessible = true
            }.invoke(activity, 15_000L)
            check(field("scrubTarget") == null)
            check(player.currentPosition == 5_000L)
        }
        Thread.sleep(500)
        check(requests.size == before) { "Old skip overrode an explicit buffered seek" }
    }

    private fun cancelOnVersionChange() {
        val before = requests.size
        main {
            prepareRestart()
            press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            activity.javaClass.getDeclaredMethod("selectVersion", MediaVersion::class.java).apply {
                isAccessible = true
            }.invoke(activity, field("version"))
            check(field("scrubTarget") == null)
        }
        Thread.sleep(500)
        check(requests.size == before) { "Old skip restarted playback after a version change" }
    }

    private fun skipWhileSourcePending() {
        val before = requests.size
        try {
            main {
                LumyApp.instance.settings.setAllowTranscode(checkNotNull(LumyApp.instance.currentEntry).id, true)
                set("quality", Quality.Limited(720, 4_000))
                prepareRestart()
                probe.holdNextSource = true
                press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            }
            await("held transcode source") { main { probe.hasPendingSource } }
            main {
                check(requests.size == before + 1)
                press(KeyEvent.KEYCODE_MEDIA_REWIND)
                check(field("scrubTarget") == 1_000L) { "Old buffer accepted a skip while its replacement was pending" }
            }
            await("replacement at the latest target") { requests.size == before + 2 }
            check(requests.last().startMs == 1_000L && requests.last().mode is PlaybackMode.Transcode)
            await("replacement playing") { main { player.isPlaying && player.currentPosition < 5_000L } }
            main { probe.releaseDelayed() }
            Thread.sleep(200)
            main {
                check((field("request") as PlaybackRequest).startMs == 1_000L)
                check(player.currentPosition < 5_000L) { "Late source replaced the final target" }
            }
        } finally {
            main { probe.releaseDelayed(); set("quality", Quality.Original) }
        }
    }

    private fun prepareRestart() {
        player.pause()
        // Inject an embedded-subtitle route: every seek must replace that transcode source.
        set("plan", Plan(PlaybackMode.Transcode(embedSubtitleId = "fixture"), emptyList()))
        set("timeBase", 0L)
        set("pendingSeekMs", 1_000L)
    }

    private fun press(code: Int) {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun field(name: String): Any? = activity.readPlaybackField(name)
    private fun set(name: String, value: Any?) = activity.writePlaybackField(name, value)

    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(30)
        }
        error("Timed out: $label")
    }
}

/** Retains one response so the older request can deliberately arrive after its replacement. */
internal class SkipPlaybackProbe {
    val requests = CopyOnWriteArrayList<PlaybackRequest>()
    var holdNextSource = false
    private var delayed: (() -> Unit)? = null
    val hasPendingSource get() = delayed != null

    fun source(request: PlaybackRequest, source: PlaybackSource, continuation: Continuation<PlaybackSource>): Any {
        requests.add(request)
        if (!holdNextSource) return source
        holdNextSource = false
        delayed = { continuation.resumeWith(Result.success(source)) }
        return COROUTINE_SUSPENDED
    }

    fun releaseDelayed() {
        val resume = delayed
        delayed = null
        resume?.invoke()
    }
}

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.hesier.lumy.LumyApp
import com.hesier.lumy.databinding.ActivityPlayerBinding
import com.hesier.lumy.ui.settings.SettingsActivity
import okhttp3.OkHttpClient
import java.io.File
import kotlin.math.abs

/** Local video avoids server credentials, watch-progress writes and network variance. */
class DanmakuProbeTest(private val instrumentation: Instrumentation) {
    private lateinit var activity: Activity
    private lateinit var binding: ActivityPlayerBinding
    private lateinit var bundle: PlayerBundle
    private lateinit var probe: DanmakuProbe
    private val preferences get() = instrumentation.targetContext.getSharedPreferences("danmaku-probe-test", android.content.Context.MODE_PRIVATE)
    private var baseMs = 600_000L
    private var droppedFrames = 0
    private var draws = 0
    private val results = mutableListOf<String>()
    private val drawListener = ViewTreeObserver.OnDrawListener { draws++ }
    private val player get() = bundle.player

    fun runAll(): String {
        check(preferences.edit().clear().commit())
        DanmakuFrameGateTest.runAll()
        main { DanmakuBudgetTest.runAll(); DanmakuAutoDensityTest.runAll(); DanmakuSettingsMappingTest.runAll() }
        results += "Input burst, Unicode length, cache queue bounds, cancellation and retry checks passed"
        val fixture = File(instrumentation.targetContext.cacheDir, "danmaku-fixture.mkv")
        instrumentation.context.assets.open("dual-subtitles/fixture.mkv").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            main { setup(fixture) }
            await("local video playing") { player.isPlaying && player.currentPosition > 500 }
            checkDelayedStart()
            DanmakuSettingsBindingTest(instrumentation, preferences).run(probe) { baseMs + player.currentPosition }
            results += "Test settings, master switch, types and signed offsets applied live"
            sample("off", 0)
            sample("normal", 1)
            checkRemote()
            checkSync()
            checkIdleAndSeek()
            sample("stress", 2)
            screenshot()
            checkRelease()
            results += "Emulator smoke measurements only; no low-end 4K/HDR performance claim"
        } finally {
            main {
                if (::probe.isInitialized) probe.release()
                if (::bundle.isInitialized) {
                    binding.root.viewTreeObserver.removeOnDrawListener(drawListener)
                    binding.playerView.player = null
                    player.release()
                    bundle.ass.release()
                    bundle.secondaryAss.release()
                }
                activity.finish()
            }
            check(preferences.edit().clear().commit())
        }
        return results.joinToString("\n")
    }

    private fun setup(file: File) {
        binding = ActivityPlayerBinding.inflate(activity.layoutInflater)
        activity.setContentView(binding.root)
        binding.controls.visibility = View.GONE
        binding.buffering.visibility = View.GONE
        val focus = Button(activity).apply { text = activity.getString(com.hesier.lumy.R.string.player_more) }
        binding.root.addView(focus, android.widget.FrameLayout.LayoutParams(140, 70))
        focus.requestFocus()
        bundle = PlayerFactory.create(activity, OkHttpClient(), emptyMap(), SyncOffsets())
        binding.playerView.player = player
        val secondary = DualSubtitleOverlay(binding.playerView, LumyApp.instance.settings, bundle.secondaryAss)
        bundle.subtitles.onCues = secondary::onSecondaryCues
        player.addListener(secondary)
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, count: Int, elapsedMs: Long) {
                droppedFrames += count
            }
        })
        probe = DanmakuProbe(binding.playerView, player, preferences = preferences) { baseMs + player.currentPosition }
        binding.root.viewTreeObserver.addOnDrawListener(drawListener)
        focus.setOnClickListener { probe.menuOption()?.second?.invoke() }
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
        player.prepare()
        player.play()
    }

    private fun checkDelayedStart() {
        val settings = com.hesier.lumy.data.DanmakuSettings(preferences)
        main {
            baseMs = 0L
            settings.enabled = true
            settings.offsetMs = 60_000L
            probe.setProfile(1)
        }
        Thread.sleep(1_200)
        main { check(probe.appliedConfig == null && probe.visibleCount == 0); baseMs = 600_000L }
        await("offset start reached") { probe.visibleCount > 0 }
        main { baseMs = 0L }
        await("seek before delayed start") { probe.appliedConfig == null && probe.visibleCount == 0 }
        main { probe.reset(); baseMs = 600_000L; settings.reset() }
        results += "Positive offset hides comments before origin and resumes after reaching it"
    }

    private fun sample(label: String, profile: Int) {
        main { probe.setProfile(profile) }
        if (profile > 0) await("visible $label comments") { probe.visibleCount > 0 }
        Thread.sleep(1_000)
        var beforeDrops = 0
        main { draws = 0; beforeDrops = droppedFrames }
        Thread.sleep(5_000)
        main {
            results += "$label: uiDraws=$draws videoDropped=${droppedFrames - beforeDrops} " +
                "visible=${probe.visibleCount} driftMs=${if (profile == 0) 0 else abs(probe.currentTimeMs - baseMs - player.currentPosition)} " +
                "queue=${probe.cacheSnapshot.contentToString()} " +
                "ui=${binding.root.width}x${binding.root.height} video=${player.videoFormat?.width}x${player.videoFormat?.height}"
            results += "  ${probe.performanceReport}"
            check(probe.cacheSnapshot[1] <= 64L) { "Cache queue exceeded its bound" }
        }
    }

    private fun checkRemote() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        await("menu opens") { !activity.hasWindowFocus() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        await("remote selects stress") { activity.hasWindowFocus() && probe.profile == 2 && probe.visibleCount > 0 }
        main { check(activity.currentFocus is Button) { "Overlay stole focus" } }
        results += "D-pad opens menu, selects density and restores focus"
    }

    private fun checkSync() {
        main { player.pause() }
        await("pause reached the frame worker") { probe.metrics.pauses > 0 }
        var pausedAt = 0L
        main { pausedAt = probe.currentTimeMs }
        Thread.sleep(400)
        main {
            check(abs(probe.currentTimeMs - pausedAt) < 30) { "Paused clock advanced" }
            player.seekTo(12_000)
        }
        await("paused seek uses absolute time") { abs(probe.currentTimeMs - baseMs - player.currentPosition) < 100 }
        main { check(!player.isPlaying); baseMs += 120_000; player.seekTo(2_000) }
        await("changed time base") { abs(probe.currentTimeMs - baseMs - player.currentPosition) < 100 }
        main { player.setPlaybackSpeed(2f); player.play() }
        Thread.sleep(2_000)
        main {
            check(abs(probe.currentTimeMs - baseMs - player.currentPosition) < 200) { "2x clock drift" }
            player.setPlaybackSpeed(1f)
            player.stop()
        }
        main { pausedAt = probe.currentTimeMs }
        Thread.sleep(300)
        main { check(abs(probe.currentTimeMs - pausedAt) < 30); player.prepare(); player.play() }
        await("restart") { player.isPlaying }
        results += "Pause, paused seek, absolute time-base change, 2x and stop/restart synchronized"
    }

    private fun checkIdleAndSeek() {
        main { player.pause() }
        await("paused comments settle") { probe.visibleCount > 0 && probe.cacheSnapshot[0] == 0L }
        Thread.sleep(500)
        var updates = 0L
        var commentDraws = 0L
        var seeks = 0
        var starts = 0
        var ids = emptyList<Long>()
        main {
            updates = probe.metrics.update.count
            commentDraws = probe.metrics.draw.count
            seeks = probe.metrics.seeks
            starts = probe.metrics.starts
            ids = probe.visibleIds
        }
        Thread.sleep(2_200)
        main {
            check(probe.metrics.update.count == updates) { "Paused updates still run" }
            check(probe.metrics.draw.count == commentDraws) { "Paused comments still redraw" }
            check(probe.metrics.seeks == seeks && probe.metrics.starts == starts) { "Idle synchronization repeated" }
            player.seekTo(if (player.currentPosition > 10_000) 5_000 else 15_000)
        }
        await("paused seek publishes a different visible snapshot") {
            !player.isPlaying && probe.visibleIds.isNotEmpty() && probe.visibleIds != ids && probe.cacheSnapshot[0] == 0L
        }
        Thread.sleep(500)
        main { updates = probe.metrics.update.count; seeks = probe.metrics.seeks }
        Thread.sleep(400)
        main {
            check(probe.metrics.update.count == updates) { "Paused seek never settled" }
            player.play()
        }
        await("resume after paused seek") { player.isPlaying && probe.metrics.update.count > updates }
        Thread.sleep(2_200)
        main { check(probe.metrics.seeks == seeks) { "Heartbeat forced another seek" } }
        results += "Paused update/draw stopped; paused seek changed visible snapshot and settled; heartbeat deduplicated"
    }

    private fun checkRelease() {
        main { probe.setProfile(0); draws = 0 }
        Thread.sleep(400)
        main { draws = 0 }
        Thread.sleep(500)
        main {
            check(probe.visibleCount == 0 && probe.profile == 0)
            val workers = Thread.getAllStackTraces().filter { (thread, stack) ->
                thread.isAlive && (thread.name in setOf("ActionThread", "AkDanmaku-Cache", "LumyDanmakuFrames") ||
                    stack.any { it.className.startsWith("com.kuaishou.akdanmaku.") })
            }.keys
            check(workers.isEmpty()) { "Leaked AkDanmaku workers: ${workers.map { it.name to it.state }}" }
            check(draws == 0) { "Off still causes UI draws: $draws" }
            results += "Off: uiDraws=$draws; no remaining AkDanmaku workers"
            probe.release()
            probe.release()
        }
        results += "Off removes overlay; release is idempotent"
    }

    private fun screenshot() { captureDanmakuTest(instrumentation, "danmaku-probe.png") }

    private fun main(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        result!!.getOrThrow()
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var passed = false
            main { player.playerError?.let { throw it }; passed = condition() }
            if (passed) return
            Thread.sleep(30)
        }
        error("Timed out: $label; $results")
    }
}

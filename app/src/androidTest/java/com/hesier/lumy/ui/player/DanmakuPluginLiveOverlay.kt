@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.media3.common.MediaItem
import com.hesier.lumy.data.DanmakuDensity
import com.hesier.lumy.data.DanmakuSettings
import com.hesier.lumy.databinding.ActivityPlayerBinding
import com.hesier.lumy.net.danmaku.DanmakuComment
import com.hesier.lumy.ui.settings.SettingsActivity
import okhttp3.OkHttpClient
import java.io.File
import kotlin.math.abs

/** The video stays local: only the downloaded plugin or explicit online fallback comments are rendered. */
internal class DanmakuPluginLiveOverlay(private val instrumentation: Instrumentation,
    private val preferences: SharedPreferences) : AutoCloseable {
    private lateinit var activity: SettingsActivity
    private lateinit var binding: ActivityPlayerBinding
    private lateinit var bundle: PlayerBundle
    private lateinit var overlay: DanmakuOverlay
    private var clockMs = 0L
    private val player get() = bundle.player

    fun run(comments: List<DanmakuComment>, sourceOffsetMs: Long): Pair<Int, Int> {
        val rolling = comments.filter { it.mode == 1 }.ifEmpty { comments }.sortedBy { it.timeMs }
        val first = rolling.first().timeMs + 2_000
        val later = rolling.firstOrNull { it.timeMs > first + 90_000 } ?: rolling.last()
        val second = later.timeMs + 2_000
        check(second != first) { "Plugin comments need distinct times for seek validation" }
        val file = File(instrumentation.targetContext.cacheDir, "danmaku-plugin-fixture.mkv")
        instrumentation.context.assets.open("dual-subtitles/fixture.mkv").use { input -> file.outputStream().use { input.copyTo(it) } }
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as SettingsActivity
        main { setup(file) }
        await("local video playing") { player.isPlaying && player.currentPosition > 500 }
        var draws = 0L
        main {
            player.pause()
            clockMs = first + sourceOffsetMs
            draws = overlay.metrics.draw.count
            overlay.setComments(comments, sourceOffsetMs)
        }
        val firstVisible = settledVisible(draws, first)
        instrumentation.waitForIdleSync()
        captureDanmakuTest(instrumentation, "danmaku-plugin-live.png")
        main { draws = overlay.metrics.draw.count; clockMs = second + sourceOffsetMs; player.seekTo(2_000) }
        val seekVisible = settledVisible(draws, second)
        main { overlay.clear(); check(overlay.appliedConfig == null && overlay.visibleCount == 0) }
        return firstVisible to seekVisible
    }

    private fun setup(file: File) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        binding = ActivityPlayerBinding.inflate(activity.layoutInflater)
        activity.setContentView(binding.root)
        binding.controls.visibility = View.GONE; binding.buffering.visibility = View.GONE
        DanmakuSettings(preferences).apply {
            enabled = true; density = DanmakuDensity.LOW
            rolling = true; top = true; bottom = true
        }
        bundle = PlayerFactory.create(activity, OkHttpClient(), emptyMap(), SyncOffsets())
        binding.playerView.player = player
        overlay = DanmakuOverlay(binding.playerView, player, { clockMs }, preferences)
        player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
        player.prepare(); player.play()
    }

    private fun settledVisible(drawBefore: Long, targetMs: Long): Int {
        var updates = -1L
        var changed = SystemClock.elapsedRealtime()
        var visible = 0
        await("plugin overlay stable and visible") {
            val current = overlay.metrics.update.count
            val now = SystemClock.elapsedRealtime()
            if (updates != current) { updates = current; changed = now }
            val count = overlay.visibleCount
            val ready = !player.isPlaying && now - changed >= 500 && overlay.metrics.draw.count > drawBefore &&
                count > 0 && abs(overlay.currentTimeMs - targetMs) < 100
            if (ready) visible = count
            ready
        }
        return visible
    }

    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 60_000
        while (SystemClock.elapsedRealtime() < end) {
            val ready = main { player.playerError?.let { error("Local video player error: ${it.errorCodeName}") }; condition() }
            if (ready) return
            Thread.sleep(50)
        }
        error("Timed out: $label")
    }

    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    override fun close() {
        if (::activity.isInitialized) main {
            if (::overlay.isInitialized) overlay.release()
            if (::bundle.isInitialized) {
                binding.playerView.player = null
                player.release(); bundle.ass.release(); bundle.secondaryAss.release()
            }
            activity.finishAndRemoveTask()
        }
    }
}

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.media3.common.MediaItem
import com.hesier.lumy.data.DanmakuDensity
import com.hesier.lumy.data.DanmakuServices
import com.hesier.lumy.data.DanmakuSettings
import com.hesier.lumy.databinding.ActivityPlayerBinding
import com.hesier.lumy.net.danmaku.DanmakuApi
import com.hesier.lumy.net.danmaku.DanmakuComment
import com.hesier.lumy.net.danmaku.DanmakuEndpoint
import com.hesier.lumy.ui.settings.SettingsActivity
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import kotlin.math.abs

/** 显式测试选集，下载真实弹幕后交给正式 Overlay；端点仅由 instrumentation 参数传入。 */
class DanmakuLiveOverlayTest(private val instrumentation: Instrumentation) {
    private lateinit var activity: Activity
    private lateinit var binding: ActivityPlayerBinding
    private lateinit var bundle: PlayerBundle
    private lateinit var overlay: DanmakuOverlay
    private val preferences get() = instrumentation.targetContext.getSharedPreferences("danmaku-live-test", Context.MODE_PRIVATE)
    private val player get() = bundle.player
    private var clockMs = 0L

    fun runAll(url: String, animeId: String): String {
        val comments = runBlocking {
            val api = DanmakuApi(DanmakuServices.http, DanmakuEndpoint(url))
            val episode = api.details(animeId).episodes.firstOrNull { it.number == "1" }
                ?: error("Explicit test episode number 1 is unavailable")
            api.comments(episode.id)
        }
        check(comments.isNotEmpty()) { "Live API returned no comments" }
        val targets = targets(comments)
        val fixture = prepareFixture()
        check(preferences.edit().clear().commit())
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var firstVisible = 0
        var seekVisible = 0
        try {
            main { setup(fixture) }
            await("local video playback") { player.isPlaying && player.currentPosition > 500 }
            firstVisible = sampleSettledVisible("live comments rendered") {
                player.pause()
                clockMs = targets.first
                overlay.setComments(comments)
            }
            instrumentation.waitForIdleSync()
            screenshot()
            seekVisible = sampleSettledVisible("live comments after seek") {
                clockMs = targets.second
                player.seekTo(2_000)
            }
            main {
                check(!player.isPlaying)
                overlay.clear()
                check(overlay.appliedConfig == null && overlay.visibleCount == 0)
                overlay.release()
                overlay.release()
            }
        } finally {
            main {
                if (::overlay.isInitialized) overlay.release()
                if (::bundle.isInitialized) {
                    binding.playerView.player = null
                    player.release()
                    bundle.ass.release()
                    bundle.secondaryAss.release()
                }
                activity.finish()
            }
            check(preferences.edit().clear().commit())
        }
        return "Live API to Overlay: comments=${comments.size}, firstVisible=$firstVisible, seekVisible=$seekVisible; seek, clear and release passed"
    }

    /** 暂停缓存可继续补帧；计数稳定且已有 UI 绘制后，在同次主线程采样中记录可见数。 */
    private fun sampleSettledVisible(label: String, change: () -> Unit): Int {
        var drawBefore = 0L
        main { drawBefore = overlay.metrics.draw.count; change() }
        var previousUpdates = -1L
        var lastChangeMs = SystemClock.elapsedRealtime()
        var visible = 0
        await(label) {
            val updates = overlay.metrics.update.count
            val now = SystemClock.elapsedRealtime()
            if (updates != previousUpdates) { previousUpdates = updates; lastChangeMs = now }
            val currentVisible = overlay.visibleCount
            val ready = !player.isPlaying && now - lastChangeMs >= CACHE_SETTLE_MS &&
                overlay.metrics.draw.count > drawBefore && currentVisible > 0 &&
                abs(overlay.currentTimeMs - clockMs) < 100
            if (ready) visible = currentVisible
            ready
        }
        check(visible > 0)
        return visible
    }

    private fun prepareFixture(): File {
        val file = File(instrumentation.targetContext.cacheDir, "danmaku-live-fixture.mkv")
        instrumentation.context.assets.open("dual-subtitles/fixture.mkv").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    private fun setup(file: File) {
        binding = ActivityPlayerBinding.inflate(activity.layoutInflater)
        activity.setContentView(binding.root)
        binding.controls.visibility = View.GONE
        binding.buffering.visibility = View.GONE
        DanmakuSettings(preferences).apply {
            enabled = true
            density = DanmakuDensity.LOW
            rolling = true
            top = true
            bottom = true
        }
        bundle = PlayerFactory.create(activity, OkHttpClient(), emptyMap(), SyncOffsets())
        binding.playerView.player = player
        overlay = DanmakuOverlay(binding.playerView, player, { clockMs }, preferences)
        player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
        player.prepare()
        player.play()
    }

    private fun targets(comments: List<DanmakuComment>): Pair<Long, Long> {
        val rolling = comments.filter { it.mode == 1 }
        val candidates = rolling.ifEmpty { comments }.sortedBy { it.timeMs }
        val first = candidates.first().timeMs + COMMENT_AGE_MS
        val later = candidates.firstOrNull { it.timeMs > first + SEEK_DISTANCE_MS }
            ?: candidates.lastOrNull { it.timeMs > first }
            ?: error("Live source needs two distinct comment times for seek validation")
        return first to later.timeMs + COMMENT_AGE_MS
    }

    private fun screenshot() { captureDanmakuTest(instrumentation, "danmaku-live.png") }

    private fun main(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        result!!.getOrThrow()
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var passed = false
            main { player.playerError?.let { throw it }; passed = condition() }
            if (passed) return
            Thread.sleep(30)
        }
        var diagnostic = ""
        main {
            diagnostic = "visible=${overlay.visibleCount}, updates=${overlay.metrics.update.count}, " +
                "draws=${overlay.metrics.draw.count}, time=${overlay.currentTimeMs}, target=$clockMs, playing=${player.isPlaying}"
        }
        error("Timed out: $label; $diagnostic")
    }

    private companion object {
        const val CACHE_SETTLE_MS = 500L
        const val COMMENT_AGE_MS = 2_000L
        const val SEEK_DISTANCE_MS = 90_000L
    }
}

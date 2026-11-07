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
import com.hesier.lumy.data.DanmakuSettings
import com.hesier.lumy.databinding.ActivityPlayerBinding
import com.hesier.lumy.net.danmaku.DanmakuComment
import com.hesier.lumy.ui.settings.SettingsActivity
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import kotlin.math.abs

/** 真实输入走正式 Overlay，使用本地视频避免任何服务端状态。 */
class DanmakuOverlayTest(private val instrumentation: Instrumentation) {
    private lateinit var activity: Activity
    private lateinit var binding: ActivityPlayerBinding
    private lateinit var bundle: PlayerBundle
    private lateinit var overlay: DanmakuOverlay
    private val preferences get() = instrumentation.targetContext.getSharedPreferences("danmaku-overlay-test", Context.MODE_PRIVATE)
    private val player get() = bundle.player
    private var baseMs = 0L

    fun runAll(): String {
        check(preferences.edit().clear().commit())
        checkWindowAdmission()
        val fixture = File(instrumentation.targetContext.cacheDir, "danmaku-overlay-fixture.mkv")
        instrumentation.context.assets.open("dual-subtitles/fixture.mkv").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            main { setup(fixture) }
            await("local playback") { player.isPlaying && player.currentPosition > 500 }
            main { player.pause() }
            checkNearOrigin()
            main { baseMs = 0; overlay.setComments(comments()) }
            await("real comments") { overlay.visibleCount > 0 }
            checkSeekAndIdle()
            DanmakuStabilityTest(instrumentation).apply {
                checkPausedRefresh(binding.playerView, overlay)
                checkPause(binding.playerView, player, overlay)
                checkFullscreen(binding.playerView)
            }
            DanmakuContinuityTest(instrumentation, binding.playerView, player).checkWindows(overlay) { target ->
                baseMs = target - player.currentPosition
            }
            checkSettingsAndOrigin()
            checkReplacementAndClear()
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
        return "Real Overlay: bounded admission, pause/seek geometry stability, full-screen aspect ratios, signed offsets, live settings, replacement, clear and release passed"
    }

    private fun setup(file: File) {
        binding = ActivityPlayerBinding.inflate(activity.layoutInflater)
        activity.setContentView(binding.root)
        binding.controls.visibility = View.GONE
        binding.buffering.visibility = View.GONE
        DanmakuSettings(preferences).apply { enabled = true; density = DanmakuDensity.HIGH }
        bundle = PlayerFactory.create(activity, OkHttpClient(), emptyMap(), SyncOffsets())
        binding.playerView.player = player
        overlay = DanmakuOverlay(binding.playerView, player, { baseMs + player.currentPosition }, preferences)
        player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
        player.prepare()
        player.play()
    }

    private fun comments(idOffset: Long = 0L) = (0L..300_000L step 125L).mapIndexed { index, time ->
        DanmakuComment(idOffset + index, time, "真实输入 $index", 1, 0xffffff)
    }

    private fun checkNearOrigin() {
        val settings = DanmakuSettings(preferences)
        for ((mode, negativeIds) in listOf(1, 4, 5).flatMap { listOf(it to false, it to true) }) {
            val source = (40L..80_000L step 520L).mapIndexed { index, time ->
                val id = if (negativeIds) Long.MIN_VALUE + index else index.toLong()
                DanmakuComment(id, time, "near origin $index", mode, 15138834)
            }
            main {
                settings.density = DanmakuDensity.LOW
                settings.bottom = true
                baseMs = 2_040 - player.currentPosition
                overlay.setComments(source)
            }
            var previous = -1L
            var lastChange = SystemClock.elapsedRealtime()
            await("near origin stable visible, mode=$mode negativeIds=$negativeIds") {
                val count = overlay.metrics.update.count
                val now = SystemClock.elapsedRealtime()
                if (count != previous) { previous = count; lastChange = now }
                now - lastChange >= 1_000 && overlay.visibleCount > 0 &&
                    abs(overlay.currentTimeMs - 2_040) < 100
            }
            if (mode == 1 && !negativeIds) checkTemporaryDensity()
            if (mode == 1 && negativeIds) captureDanmakuTest(instrumentation, "danmaku-origin.png")
            main { overlay.clear() }
        }
        main { settings.density = DanmakuDensity.HIGH; settings.bottom = false }
    }

    private fun checkTemporaryDensity() {
        val saved = DanmakuSettings(preferences).density
        // 仅测试访问内部 worker，确保调用实际的临时密度变更路径。
        val field = DanmakuOverlay::class.java.getDeclaredField("driver").apply { isAccessible = true }
        var driver: DanmakuFrameDriver? = null
        main { driver = field.get(overlay) as DanmakuFrameDriver }
        var before = emptyMap<Long, android.graphics.RectF>()
        main { before = geometry() }
        check(before.isNotEmpty())
        for (divisor in listOf(2, 4, 2, 1)) {
            main { driver!!.setDensityDivisor(divisor) }
            var previous = -1L
            var changedAt = SystemClock.elapsedRealtime()
            await("temporary density $divisor retains visible comments") {
                val updates = overlay.metrics.update.count
                val now = SystemClock.elapsedRealtime()
                if (previous != updates) { previous = updates; changedAt = now }
                val filter = overlay.appliedConfig?.dataFilter?.filterIsInstance<DanmakuDensityFilter>()?.single()
                now - changedAt >= 500 && filter?.divisor == divisor && overlay.visibleCount > 0
            }
            main {
                check(before == geometry()) { "Density $divisor changed existing IDs or geometry" }
                check(saved == DanmakuSettings(preferences).density)
            }
        }
    }

    private fun geometry(): Map<Long, android.graphics.RectF> {
        val engine = DanmakuOverlay::class.java.getDeclaredField("engine").apply { isAccessible = true }
            .get(overlay) as com.kuaishou.akdanmaku.ui.DanmakuPlayer
        return engine.getDanmakusInRect(android.graphics.RectF(0f, 0f, binding.playerView.width.toFloat(),
            binding.playerView.height.toFloat())).orEmpty().associate { it.data.danmakuId to android.graphics.RectF(it.rect) }
    }

    private fun checkSeekAndIdle() {
        var count = 0L
        main { baseMs = 120_000; player.seekTo(2_000) }
        await("seek reuses source outside first window") {
            overlay.visibleCount > 0 && abs(overlay.currentTimeMs - baseMs - player.currentPosition) < 100
        }
        var previous = -1L
        var lastChange = SystemClock.elapsedRealtime()
        await("paused cache callbacks settle") {
            val current = overlay.metrics.update.count
            if (current != previous) { previous = current; lastChange = SystemClock.elapsedRealtime() }
            SystemClock.elapsedRealtime() - lastChange >= 1_000
        }
        main { count = overlay.metrics.update.count }
        Thread.sleep(1_300)
        main {
            check(count == overlay.metrics.update.count) { "Paused overlay continued scheduling" }
            check(overlay.visibleCount > 0) { "Paused cache completion hid existing comments" }
            baseMs = 0
            player.seekTo(3_000)
        }
        await("backward seek reuses retained source") { overlay.visibleCount > 0 && overlay.currentTimeMs < 10_000 }
    }

    private fun checkSettingsAndOrigin() {
        val settings = DanmakuSettings(preferences)
        main { settings.enabled = false }
        await("master off") { overlay.appliedConfig == null && overlay.visibleCount == 0 }
        main { settings.enabled = true }
        await("master restored") { overlay.visibleCount > 0 }
        main { settings.rolling = false; settings.top = false; settings.bottom = false }
        await("all modes off") { overlay.appliedConfig == null }
        main { settings.rolling = true; settings.offsetMs = 60_000 }
        await("before positive origin") { overlay.appliedConfig == null }
        main { baseMs = 120_000; player.seekTo(4_000) }
        await("cross origin") { overlay.visibleCount > 0 && abs(overlay.currentTimeMs - 64_000) < 100 }
        main { settings.offsetMs = -1_000 }
        await("negative user offset") { abs(overlay.currentTimeMs - 125_000) < 100 }
        main { settings.offsetMs = 0; overlay.setComments(comments(), sourceOffsetMs = 2_000) }
        await("source offset") { overlay.visibleCount > 0 && abs(overlay.currentTimeMs - 122_000) < 100 }
    }

    private fun checkReplacementAndClear() {
        main {
            overlay.setComments(comments(10_000))
            overlay.setComments(comments(20_000))
        }
        await("latest generation only") { overlay.visibleIds.isNotEmpty() && overlay.visibleIds.all { it >= 20_000 } }
        main { overlay.clear() }
        Thread.sleep(300)
        main { check(overlay.visibleCount == 0 && overlay.appliedConfig == null) }
        main { overlay.setComments(comments()); overlay.clear() }
        Thread.sleep(300)
        main {
            check(overlay.appliedConfig == null) { "Cancelled input recreated the engine" }
            overlay.release()
            overlay.release()
        }
    }

    private fun checkWindowAdmission() = runBlocking {
        val settings = DanmakuTestPreferences.read(DanmakuSettings(preferences))
        val source = DanmakuWindow.sort(comments().reversed())
        val admitted = DanmakuWindow.admit(source, settings)
        val window = DanmakuWindow.prepare(admitted, 100_000, settings)
        check(window.items.isNotEmpty())
        check(window.items.all { it.position in 88_000L..160_000L })
        window.items.forEachIndexed { index, item ->
            val end = window.items.indexOfLast { it.position < item.position + 500 }
            check(end - index + 1 <= settings.admissionLimit)
        }
        val unsupported = listOf(DanmakuComment(1, 100_000, "ignored", 6, 0xffffff))
        check(DanmakuWindow.admit(unsupported, settings).isEmpty())
        checkOverlappingWindows(source, settings)
    }

    private suspend fun checkOverlappingWindows(source: List<DanmakuComment>, settings: DanmakuTestPreferences) {
        for (density in DanmakuDensity.entries) {
            val style = settings.copy(density = density)
            val admitted = DanmakuWindow.admit(source, style)
            val first = DanmakuWindow.prepare(admitted, 100_125, style)
            val second = DanmakuWindow.prepare(admitted, 130_375, style)
            val shared = (second.positionMs - DanmakuWindow.PREROLL_MS)..first.endMs
            val firstIds = first.items.filter { it.position in shared }.map { it.danmakuId }
            val secondIds = second.items.filter { it.position in shared }.map { it.danmakuId }
            check(firstIds.isNotEmpty() && firstIds == secondIds) { "Overlapping windows changed admitted IDs: $density" }
        }
    }

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
        var diagnostic = ""
        main {
            val parent = binding.playerView
            diagnostic = "time=${overlay.currentTimeMs} visible=${overlay.visibleCount} " +
                "${overlay.metrics.report(null)} host=${parent.width}x${parent.height} " +
                "shown=${parent.isShown} children=${parent.childCount}"
        }
        error("Timed out: $label; $diagnostic")
    }
}

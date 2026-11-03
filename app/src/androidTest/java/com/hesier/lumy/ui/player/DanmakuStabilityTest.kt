@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.graphics.RectF
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.kuaishou.akdanmaku.ui.DanmakuPlayer

/** Checks actual published geometry, rather than only nonzero comment counts. */
internal class DanmakuStabilityTest(private val instrumentation: Instrumentation) {
    fun checkPausedRefresh(host: PlayerView, overlay: DanmakuOverlay) {
        val engine = field(overlay, "engine") as DanmakuPlayer
        val driver = field(overlay, "driver") as DanmakuFrameDriver
        var previous = -1L
        var changedAt = SystemClock.elapsedRealtime()
        await("paused cache drain") {
            val count = overlay.metrics.update.count
            val now = SystemClock.elapsedRealtime()
            if (count != previous) { previous = count; changedAt = now }
            now - changedAt >= 1_000 && overlay.visibleCount > 0
        }
        var before = emptyMap<Long, RectF>()
        main { before = snapshot(host, engine) }
        check(before.isNotEmpty())
        repeat(5) {
            val count = overlay.metrics.update.count
            driver.requestFrame()
            await("paused refresh") { overlay.metrics.update.count > count }
            Thread.sleep(100)
            main {
                val after = snapshot(host, engine)
                check(before == after) { "Paused refresh changed geometry: before=$before after=$after" }
            }
        }
    }

    fun checkPause(host: PlayerView, player: Player, overlay: DanmakuOverlay) {
        main { player.play() }
        await("playing comments") { player.isPlaying && overlay.visibleCount > 0 }
        Thread.sleep(500)
        val engine = field(overlay, "engine") as DanmakuPlayer
        var before = emptyMap<Long, RectF>()
        main { before = snapshot(host, engine); player.pause() }
        Thread.sleep(300)
        main {
            val after = snapshot(host, engine)
            val shared = before.keys.intersect(after.keys)
            check(shared.isNotEmpty())
            check(shared.all { before.getValue(it).top == after.getValue(it).top }) {
                "Pause moved existing comments to another lane"
            }
        }
        checkPausedRefresh(host, overlay)
    }

    fun checkFullscreen(host: PlayerView) {
        val content = host.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
        val original = content.videoAspectRatioForTest()
        try {
            for (ratio in listOf(2.4f, 4f / 3f)) {
                main { content.setAspectRatio(ratio) }
                Thread.sleep(200)
                main {
                    val layer = findLayer(host) ?: error("Missing comment layer")
                    check(layer.width == host.width && layer.height == host.height) {
                        "Comment viewport follows video ratio $ratio: ${layer.width}x${layer.height}"
                    }
                    val location = IntArray(2)
                    val hostLocation = IntArray(2)
                    layer.getLocationOnScreen(location)
                    host.getLocationOnScreen(hostLocation)
                    check(location.contentEquals(hostLocation)) { "Comment viewport is offset by letterboxing" }
                }
            }
        } finally {
            main { content.setAspectRatio(original) }
        }
    }

    private fun AspectRatioFrameLayout.videoAspectRatioForTest(): Float =
        AspectRatioFrameLayout::class.java.getDeclaredField("videoAspectRatio").run {
            isAccessible = true
            getFloat(this@videoAspectRatioForTest)
        }

    private fun findLayer(group: android.view.ViewGroup): DanmakuLayer? {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child is DanmakuLayer) return child
            if (child is android.view.ViewGroup) findLayer(child)?.let { return it }
        }
        return null
    }

    private fun snapshot(host: PlayerView, engine: DanmakuPlayer): Map<Long, RectF> =
        engine.getDanmakusInRect(RectF(0f, 0f, host.width.toFloat(), host.height.toFloat())).orEmpty()
            .associate { it.data.danmakuId to RectF(it.rect) }

    private fun field(overlay: DanmakuOverlay, name: String): Any =
        DanmakuOverlay::class.java.getDeclaredField(name).run { isAccessible = true; get(overlay)!! }

    private fun main(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        result!!.getOrThrow()
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var passed = false
            main { passed = condition() }
            if (passed) return
            Thread.sleep(30)
        }
        error("Timed out: $label")
    }
}

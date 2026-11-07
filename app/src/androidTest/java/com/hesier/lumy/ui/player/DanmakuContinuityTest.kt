@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.graphics.RectF
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.kuaishou.akdanmaku.ui.DanmakuPlayer
import kotlin.math.abs

/** Exercises real rolling-window handoffs without waiting a minute between boundaries. */
internal class DanmakuContinuityTest(
    private val instrumentation: Instrumentation,
    private val host: PlayerView,
    private val player: Player,
) {
    fun checkWindows(overlay: DanmakuOverlay, setPosition: (Long) -> Unit) {
        var originalPosition = 0L
        main { originalPosition = overlay.currentTimeMs }
        repeat(3) {
            var boundary = 0L
            var engine: DanmakuPlayer? = null
            main {
                player.pause()
                engine = field(overlay, "engine") as DanmakuPlayer
                boundary = (field(overlay, "active") as DanmakuWindow).endMs
                setPosition(boundary - 1_500)
                sync(overlay, true)
            }
            await("seek just before handoff") {
                val visible = engine!!.getDanmakusInRect(RectF(0f, 0f, host.width.toFloat(), host.height.toFloat())).orEmpty()
                abs(overlay.currentTimeMs - boundary + 1_500) < 100 && visible.isNotEmpty() &&
                    visible.all { it.data.position >= boundary - DanmakuWindow.PREROLL_MS }
            }
            DanmakuStabilityTest(instrumentation).checkPausedRefresh(host, overlay)
            main {
                DanmakuOverlay::class.java.getDeclaredMethod("prepare", Long::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(overlay, boundary, false)
            }
            await("next window ready") { field(overlay, "prepared") != null }
            playAcross(overlay, engine!!, boundary)
        }
        main { setPosition(originalPosition); sync(overlay, true) }
        await("rewind after trimming") {
            abs(overlay.currentTimeMs - originalPosition) < 100 && overlay.visibleCount > 0
        }
    }

    private fun playAcross(overlay: DanmakuOverlay, engine: DanmakuPlayer, boundary: Long) {
        var previous = emptyMap<Long, RectF>()
        var seeks = 0
        var crossed = false
        main { previous = geometry(engine); seeks = overlay.metrics.seeks; player.play() }
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!crossed && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(30)
            main {
                check(engine === field(overlay, "engine")) { "Window rollover replaced the engine" }
                val now = overlay.currentTimeMs
                val after = geometry(engine)
                // Ignore comments near their natural exit; all others must retain their lane.
                for ((id, rect) in previous.filterValues { it.right > host.width / 2f }) {
                    check(after[id]?.top == rect.top) { "Handoff lost or moved comment $id at $now" }
                }
                previous = after
                crossed = (field(overlay, "active") as DanmakuWindow).positionMs >= boundary
            }
        }
        main {
            player.pause()
            check(crossed) { "Window did not advance at $boundary" }
            check(seeks == overlay.metrics.seeks) { "Window rollover sought the engine clock" }
            val data = AkDanmakuAccess.dataSystem(engine)
            val sorted = data.javaClass.getDeclaredField("sortedData").apply { isAccessible = true }
                .get(data) as List<*>
            val window = field(overlay, "active") as DanmakuWindow
            check(sorted.size <= window.items.size) { "Window input accumulated: ${sorted.size}" }
        }
    }

    private fun geometry(engine: DanmakuPlayer): Map<Long, RectF> = engine.getDanmakusInRect(
        RectF(0f, 0f, host.width.toFloat(), host.height.toFloat())).orEmpty()
        .associate { it.data.danmakuId to RectF(it.rect) }

    private fun field(overlay: DanmakuOverlay, name: String): Any? =
        DanmakuOverlay::class.java.getDeclaredField(name).apply { isAccessible = true }.get(overlay)

    private fun sync(overlay: DanmakuOverlay, seek: Boolean) {
        DanmakuOverlay::class.java.getDeclaredMethod("sync", Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(overlay, seek, false)
    }

    private fun main(block: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
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

package com.hesier.lumy.ui.player

import android.graphics.Canvas
import android.os.Debug
import android.widget.FrameLayout
import com.kuaishou.akdanmaku.LumyCacheBudget
import com.kuaishou.akdanmaku.ui.DanmakuPlayer
import java.util.Locale

/** Bounded wall-clock samples of CPU update and recording paths; these are not GPU times or presented frame rates. */
internal class DanmakuMetrics {
    val update = Samples()
    val draw = Samples()
    val intervals = Samples()
    private var lastDrawNs = 0L
    @Volatile var seeks = 0
    @Volatile var starts = 0
    @Volatile var pauses = 0
    @Volatile var speedChanges = 0
    var corrections = 0

    fun drew(startNs: Long) {
        draw.record(System.nanoTime() - startNs)
        if (lastDrawNs != 0L) intervals.record(startNs - lastDrawNs)
        lastDrawNs = startNs
    }

    fun resetIntervals() { lastDrawNs = 0L }

    fun report(player: DanmakuPlayer?): String {
        val queue = player?.let { LumyCacheBudget.snapshot(AkDanmakuAccess.cacheManager(it)) } ?: LongArray(5)
        val runtime = Runtime.getRuntime()
        return "update=${update.report()} draw=${draw.report()} interval=${intervals.report()} " +
            "queue=${queue[0]} peak=${queue[1]} rejected=${queue[2]} completed=${queue[3]} cacheWorkNs=${queue[4]} " +
            "cacheHit=${player?.cacheHit?.let { "${it.num}/${it.den}" }} javaHeap=${runtime.totalMemory() - runtime.freeMemory()} " +
            "nativeHeap=${Debug.getNativeHeapAllocatedSize()} seeks=$seeks starts=$starts pauses=$pauses " +
            "speedChanges=$speedChanges corrections=$corrections"
    }

    class Samples {
        private val values = LongArray(CAPACITY)
        private var index = 0
        var count = 0L
            @Synchronized get
            private set

        @Synchronized fun record(nanos: Long) {
            values[index] = nanos
            index = (index + 1) % CAPACITY
            count++
        }

        @Synchronized fun p95Nanos(): Long {
            val size = count.coerceAtMost(CAPACITY.toLong()).toInt()
            return if (size == 0) 0L else values.copyOf(size).apply { sort() }[((size - 1) * P95).toInt()]
        }

        @Synchronized fun report(): String {
            val size = count.coerceAtMost(CAPACITY.toLong()).toInt()
            if (size == 0) return "0"
            val sorted = values.copyOf(size).apply { sort() }
            val p95 = sorted[((size - 1) * P95).toInt()] / NANOS_PER_MS
            return String.format(Locale.US, "%d/p95=%.2fms/max=%.2fms", count, p95, sorted.last() / NANOS_PER_MS)
        }
    }

    private companion object {
        const val CAPACITY = 256
        const val P95 = 0.95
        const val NANOS_PER_MS = 1_000_000.0
    }
}

/** Times only the comment subtree's UI-thread display-list recording. */
internal class DanmakuLayer(context: android.content.Context, private val metrics: DanmakuMetrics) : FrameLayout(context) {
    var onDrawn: (() -> Unit)? = null

    override fun dispatchDraw(canvas: Canvas) {
        val started = System.nanoTime()
        super.dispatchDraw(canvas)
        metrics.drew(started)
        onDrawn?.invoke()
    }
}

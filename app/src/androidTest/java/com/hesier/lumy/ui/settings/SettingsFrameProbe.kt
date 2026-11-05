package com.hesier.lumy.ui.settings

import android.annotation.TargetApi
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.Window
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.R
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Category transitions only; no preference changes and no production per-frame instrumentation. */
class SettingsFrameProbe(private val instrumentation: Instrumentation) {
    private lateinit var activity: SettingsActivity
    private var operationsProbe: SettingsLayoutOperationsProbe? = null

    fun runAll(rounds: Int = 2, operations: Boolean = false): String {
        if (Build.VERSION.SDK_INT < 24) return "Settings FrameMetrics probe requires API 24+"
        require(rounds > 0)
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as SettingsActivity
        val thread = HandlerThread("settings-frame-probe").apply { start() }
        try {
            await { main { activity.hasWindowFocus() && categoryPosition() == 0 } }
            if (operations) main {
                operationsProbe = SettingsLayoutOperationsProbe(activity.findViewById(R.id.list))
            }
            settle()
            return collect(rounds, Handler(thread.looper))
        } finally {
            main { operationsProbe?.close(); activity.finishAndRemoveTask() }
            thread.quitSafely()
            thread.join(2_000)
        }
    }

    @TargetApi(24)
    private fun collect(rounds: Int, handler: Handler): String {
        val steps = mutableListOf<Step>()
        val visits = IntArray(CATEGORIES.size).apply { this[0] = 1 }
        var previous = 0
        repeat(rounds) {
            val sequence = (1 until CATEGORIES.size).toList() + (CATEGORIES.lastIndex - 1 downTo 0).toList()
            for (category in sequence) {
                val phase = if (visits[category]++ == 0) "cold" else "warm"
                steps += measure(previous, category, phase, handler)
                previous = category
            }
        }
        val output = File(instrumentation.targetContext.cacheDir, "settings-frame-probe.csv")
        output.writeText(csv(steps))
        return buildString {
            appendLine("Settings FrameMetrics sdk=${Build.VERSION.SDK_INT}; cold=first category visit in this Activity, not process cold start")
            appendLine("layoutMeasure combines measure/layout; dropped counts missing reports, not janky frames")
            if (operationsProbe != null) appendLine("operations=count/sumMs; lmLayout includes nested create/bind/rowMeasure/rowLayout; outsideLayout may include prefetch")
            appendLine(main {
                val config = activity.resources.configuration
                val list = activity.findViewById<RecyclerView>(R.id.list)
                "language=${config.locale.toLanguageTag()} fontScale=${config.fontScale} " +
                    "densityDpi=${config.densityDpi} listPx=${list.width}x${list.height}"
            })
            steps.forEach { step ->
                appendLine("${step.phase} ${step.from}->${step.category} frames=${step.frames.size} " +
                    "dropped=${step.dropped} rows=${step.rows} visibleTextChars=${step.textChars} " +
                    "dispatchMs=${ms(step.dispatchNs)} totalP95Ms=${ms(percentile(step.frames.map { it.totalNs }, .95))} " +
                    "layoutMaxMs=${ms(step.frames.maxOfOrNull { it.layoutNs } ?: 0)} " +
                    "drawMaxMs=${ms(step.frames.maxOfOrNull { it.drawNs } ?: 0)} ${step.operations}")
            }
            append("csv=${output.absolutePath}")
        }
    }

    @TargetApi(24)
    private fun measure(from: Int, category: Int, phase: String, handler: Handler): Step {
        val frames = mutableListOf<Frame>()
        var dropped = 0
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
            // Only copy scalars here: the framework reuses metrics and can drop reports if this callback stalls.
            frames += Frame(
                metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                metrics.getMetric(FrameMetrics.DRAW_DURATION),
                metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION),
                metrics.getMetric(FrameMetrics.ANIMATION_DURATION),
                metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION),
                metrics.getMetric(FrameMetrics.SYNC_DURATION),
                metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
                metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
            )
            dropped += lost
        }
        main {
            operationsProbe?.reset()
            activity.window.addOnFrameMetricsAvailableListener(listener, handler)
        }
        val dispatchStart = System.nanoTime()
        val dispatchNs: Long
        try {
            instrumentation.sendKeyDownUpSync(if (category > from) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
            dispatchNs = System.nanoTime() - dispatchStart
            await { main { categoryPosition() == category } }
            settle()
        } finally {
            main { activity.window.removeOnFrameMetricsAvailableListener(listener) }
            val drained = CountDownLatch(1)
            handler.post { drained.countDown() }
            check(drained.await(3, TimeUnit.SECONDS)) { "FrameMetrics callback handler did not drain" }
        }
        val (rows, chars) = main {
            val list = activity.findViewById<RecyclerView>(R.id.list)
            val chars = (0 until list.childCount).sumOf { index ->
                val row = list.getChildAt(index)
                listOf(R.id.label, R.id.detail, R.id.value).sumOf { row.findViewById<TextView>(it).text.length }
            }
            (list.adapter?.itemCount ?: 0) to chars
        }
        check(frames.isNotEmpty()) { "No FrameMetrics reports for $phase ${CATEGORIES[category]}" }
        return Step(CATEGORIES[from], CATEGORIES[category], phase, frames.toList(), dropped, rows, chars, dispatchNs,
            main { operationsProbe?.summary().orEmpty() })
    }

    private fun settle() {
        SystemClock.sleep(400)
        instrumentation.waitForIdleSync()
        await { main {
            val list = activity.findViewById<RecyclerView>(R.id.list)
            val categories = activity.findViewById<RecyclerView>(R.id.categories)
            !list.isComputingLayout && !list.isLayoutRequested && !list.isAnimating &&
                list.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                !categories.isComputingLayout && categories.scrollState == RecyclerView.SCROLL_STATE_IDLE
        } }
        // Allow the render thread to finish reporting the last settled frame before removing the listener.
        SystemClock.sleep(100)
    }

    private fun categoryPosition(): Int {
        val list = activity.findViewById<RecyclerView>(R.id.categories)
        val focused = activity.currentFocus ?: return -1
        return list.findContainingViewHolder(focused)?.bindingAdapterPosition ?: -1
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 6_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Settings frame probe timed out" }
            SystemClock.sleep(30)
        }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun csv(steps: List<Step>): String = buildString {
        appendLine("step,phase,from,category,frame,droppedReportsInStep,rows,visibleTextChars,dispatchMs,totalMs,layoutMeasureMs,drawMs,inputMs,animationMs,unknownDelayMs,syncMs,commandMs,swapMs")
        steps.forEachIndexed { stepIndex, step ->
            step.frames.forEachIndexed { frameIndex, frame ->
                appendLine(listOf(stepIndex, step.phase, step.from, step.category, frameIndex, step.dropped,
                    step.rows, step.textChars, ms(step.dispatchNs), ms(frame.totalNs), ms(frame.layoutNs),
                    ms(frame.drawNs), ms(frame.inputNs), ms(frame.animationNs), ms(frame.unknownNs),
                    ms(frame.syncNs), ms(frame.commandNs), ms(frame.swapNs)).joinToString(","))
            }
        }
    }

    private fun ms(ns: Long) = if (ns < 0) "NA" else String.format(Locale.ROOT, "%.3f", ns / 1_000_000.0)

    private fun percentile(values: List<Long>, fraction: Double): Long =
        values.sorted()[(kotlin.math.ceil(values.size * fraction).toInt() - 1).coerceAtLeast(0)]

    private data class Frame(val totalNs: Long, val layoutNs: Long, val drawNs: Long, val inputNs: Long,
        val animationNs: Long, val unknownNs: Long, val syncNs: Long, val commandNs: Long, val swapNs: Long)

    private data class Step(val from: String, val category: String, val phase: String, val frames: List<Frame>,
        val dropped: Int, val rows: Int, val textChars: Int, val dispatchNs: Long, val operations: String)

    private companion object {
        val CATEGORIES = listOf("display", "playback", "audio", "subtitles", "danmaku", "compatibility", "servers", "about")
    }
}

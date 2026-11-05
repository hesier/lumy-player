package com.hesier.lumy.ui.settings

import android.annotation.TargetApi
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.R
import com.hesier.lumy.databinding.ItemSettingBinding
import java.io.File
import java.util.Locale

/** Isolated fresh-widget measurement, not process-cold timings or a replacement for window FrameMetrics. */
class SettingsRowMeasureProbe(private val instrumentation: Instrumentation) {
    private lateinit var activity: SettingsActivity

    fun runAll(): String {
        if (Build.VERSION.SDK_INT < 23) return "Settings row break-strategy probe requires API 23+"
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as SettingsActivity
        val rows = mutableListOf<String>()
        try {
            await { main { activity.hasWindowFocus() && categoryPosition() == 0 } }
            settle()
            repeat(8) { category ->
                if (category > 0) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                    await { main { categoryPosition() == category } }
                    settle()
                }
                rows += main { measureCategory(category) }
            }
        } finally {
            main { activity.finishAndRemoveTask() }
        }
        val file = File(instrumentation.targetContext.cacheDir, "settings-row-measure-probe.csv")
        file.writeText("category,row,repeat,mode,strategy,hyphenation,chars,inflateMs,bindMs,measureMs,height,labelLines,detailLines\n" +
            rows.joinToString("\n", postfix = "\n"))
        return "Fresh widget baseline/simple A/B (alternating order, 4 samples per visible row/mode); " +
            "font and process caches already warmed by screen; frame probe must be run separately. csv=${file.absolutePath}"
    }

    @TargetApi(23)
    private fun measureCategory(category: Int): List<String> {
        val list = activity.findViewById<RecyclerView>(R.id.list)
        val categories = activity.findViewById<RecyclerView>(R.id.categories)
        check(categories.findContainingViewHolder(checkNotNull(activity.currentFocus))?.bindingAdapterPosition == category)
        return buildList {
            repeat(list.childCount) { index ->
                val source = list.getChildAt(index)
                repeat(4) { iteration ->
                    val modes = if (iteration % 2 == 0) listOf(false, true) else listOf(true, false)
                    modes.forEach { simple ->
                        add(measureRow(source, simple).let { "$category,${list.getChildAdapterPosition(source)},$iteration,$it" })
                    }
                }
            }
        }
    }

    @TargetApi(23)
    private fun measureRow(source: View, simple: Boolean): String {
        val start = System.nanoTime()
        val copy = ItemSettingBinding.inflate(LayoutInflater.from(activity), null, false)
        val inflated = System.nanoTime()
        val label = source.findViewById<TextView>(R.id.label)
        val detail = source.findViewById<TextView>(R.id.detail)
        val value = source.findViewById<TextView>(R.id.value)
        copy.detail.maxLines = detail.maxLines
        copy.detail.visibility = detail.visibility
        val icon = source.findViewById<android.widget.ImageView>(R.id.icon)
        copy.icon.visibility = icon.visibility
        copy.icon.setImageDrawable(icon.drawable)
        listOf(copy.label to label, copy.detail to detail, copy.value to value).forEach { (target, original) ->
            target.breakStrategy = if (simple) Layout.BREAK_STRATEGY_SIMPLE else original.breakStrategy
            // Keep hyphenation unchanged to isolate the break-strategy variable.
            target.hyphenationFrequency = original.hyphenationFrequency
            target.text = original.text
        }
        val bound = System.nanoTime()
        copy.root.measure(View.MeasureSpec.makeMeasureSpec(source.measuredWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val measured = System.nanoTime()
        return listOf(if (simple) "simple" else "baseline", copy.detail.breakStrategy,
            copy.detail.hyphenationFrequency, label.text.length + detail.text.length + value.text.length,
            ms(inflated - start), ms(bound - inflated), ms(measured - bound), copy.root.measuredHeight,
            copy.label.lineCount, copy.detail.lineCount).joinToString(",")
    }

    private fun settle() {
        SystemClock.sleep(500)
        instrumentation.waitForIdleSync()
        check(main { activity.hasWindowFocus() })
    }

    private fun categoryPosition(): Int {
        val categories = activity.findViewById<RecyclerView>(R.id.categories)
        val focused = activity.currentFocus ?: return -1
        return categories.findContainingViewHolder(focused)?.bindingAdapterPosition ?: -1
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 6_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Settings row probe timed out" }
            SystemClock.sleep(30)
        }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun ms(ns: Long) = String.format(Locale.ROOT, "%.3f", ns / 1_000_000.0)
}

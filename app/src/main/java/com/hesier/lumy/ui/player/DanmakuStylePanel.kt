package com.hesier.lumy.ui.player

import android.content.Context
import com.hesier.lumy.R
import com.hesier.lumy.data.DanmakuDensity
import com.hesier.lumy.data.DanmakuSettings
import com.hesier.lumy.data.DanmakuSize
import com.hesier.lumy.data.DanmakuSpeed
import com.hesier.lumy.data.DanmakuStroke
import com.hesier.lumy.ui.widget.Option
import com.hesier.lumy.ui.widget.OptionsPanel
import com.hesier.lumy.ui.widget.StepperPanel
import kotlin.math.abs
import kotlin.reflect.KMutableProperty0

internal class DanmakuStylePanel(
    private val context: Context,
    private val settings: DanmakuSettings,
    private val onBack: () -> Unit,
) {
    private data class Row(val title: Int, val detail: String, val open: (Int) -> Unit)

    fun show(focused: Int = 0) {
        val rows = rows()
        val options = rows.mapIndexed { index, row ->
            Option(text(row.title), row.detail, selected = index == focused)
        }
        OptionsPanel(context, text(R.string.player_danmaku_settings), options,
            hint = text(R.string.player_style_saved_hint)) { index ->
            rows[index].open(index)
        }.apply { setOnCancelListener { onBack() } }.show()
    }

    private fun rows(): List<Row> = listOf(
        choiceRow(R.string.danmaku_density, labels(DanmakuDensity.entries,
            listOf(R.string.danmaku_density_low, R.string.danmaku_standard, R.string.danmaku_density_high)), settings::density),
        choiceRow(R.string.danmaku_area, DanmakuSettings.AREA_OPTIONS.map {
            it to if (it == 100) text(R.string.danmaku_area_full) else context.getString(R.string.danmaku_area_top, it)
        }, settings::areaPercent),
        choiceRow(R.string.danmaku_size, labels(DanmakuSize.entries,
            listOf(R.string.subtitle_size_xsmall, R.string.subtitle_size_small, R.string.danmaku_standard,
                R.string.subtitle_size_large, R.string.subtitle_size_xlarge)), settings::size),
        choiceRow(R.string.danmaku_opacity, DanmakuSettings.OPACITY_OPTIONS.map {
            it to context.getString(R.string.danmaku_percent, it)
        }, settings::opacityPercent),
        choiceRow(R.string.danmaku_speed, labels(DanmakuSpeed.entries,
            listOf(R.string.danmaku_speed_slow, R.string.danmaku_standard, R.string.danmaku_speed_fast)), settings::speed),
        choiceRow(R.string.danmaku_stroke, labels(DanmakuStroke.entries,
            listOf(R.string.off, R.string.danmaku_stroke_thin, R.string.danmaku_standard,
                R.string.danmaku_stroke_thick)), settings::stroke),
        Row(R.string.danmaku_types, typesSummary(), ::chooseTypes),
        Row(R.string.danmaku_offset, offsetLabel(settings.offsetMs), ::chooseOffset),
    )

    private fun <T> labels(values: List<T>, resources: List<Int>) = values.zip(resources.map(::text))

    private fun <T> choiceRow(title: Int, options: List<Pair<T, String>>, preference: KMutableProperty0<T>): Row =
        Row(title, options.first { it.first == preference.get() }.second) { focused ->
            OptionsPanel(context, text(title), options.map { Option(it.second, selected = it.first == preference.get()) }) {
                preference.set(options[it].first)
                show(focused)
            }.apply { setOnCancelListener { show(focused) } }.show()
        }

    private fun typeOptions() = listOf(
        R.string.danmaku_type_rolling to settings::rolling,
        R.string.danmaku_type_top to settings::top,
        R.string.danmaku_type_bottom to settings::bottom,
    )

    private fun typesSummary(): String = typeOptions().filter { it.second.get() }
        .joinToString(" · ") { text(it.first) }.ifEmpty { text(R.string.off) }

    private fun chooseTypes(focused: Int) {
        val types = typeOptions()
        OptionsPanel(context, text(R.string.danmaku_types), types.map { Option(text(it.first), selected = it.second.get()) },
            multi = true) { index ->
            val preference = types[index].second
            preference.set(!preference.get())
        }.apply { setOnCancelListener { show(focused) } }.show()
    }

    private fun chooseOffset(focused: Int) {
        OptionsPanel(context, text(R.string.danmaku_offset), listOf(
            Option(text(R.string.danmaku_offset_adjust), offsetLabel(settings.offsetMs), selected = true),
            Option(text(R.string.danmaku_offset_reset)),
        )) { index ->
            if (index == 0) {
                adjustOffset(focused)
            } else {
                settings.offsetMs = 0
                show(focused)
            }
        }.apply { setOnCancelListener { show(focused) } }.show()
    }

    private fun adjustOffset(focused: Int) {
        StepperPanel(context, text(R.string.danmaku_offset), text(R.string.danmaku_offset_hint), settings.offsetMs,
            DanmakuSettings.OFFSET_STEP_MS, DanmakuSettings.OFFSET_STEP_MS, DanmakuSettings.OFFSET_RANGE, ::offsetValue,
        ) { settings.offsetMs = it }.apply { setOnDismissListener { show(focused) } }.show()
    }

    private fun offsetValue(value: Long): String = if (value == 0L) text(R.string.danmaku_offset_zero)
        else context.getString(R.string.danmaku_offset_signed, value / MILLIS_PER_SECOND)

    private fun offsetLabel(value: Long): String = if (value == 0L) text(R.string.danmaku_offset_zero)
        else context.getString(if (value < 0) R.string.danmaku_offset_earlier else R.string.danmaku_offset_later,
            abs(value) / MILLIS_PER_SECOND)

    private fun text(id: Int) = context.getString(id)

    private companion object { const val MILLIS_PER_SECOND = 1_000.0 }
}

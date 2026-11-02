package com.hesier.lumy.ui.settings

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

/** 只编辑全局偏好，不依赖弹幕来源或播放器实现。 */
internal class DanmakuSettingsSection(
    private val context: Context,
    private val settings: DanmakuSettings,
    private val changed: () -> Unit,
) {
    fun rows(): List<SettingsActivity.Row> = listOf(
        toggleRow(R.string.danmaku_enabled, settings::enabled),
        choiceRow(R.string.danmaku_density, labels(DanmakuDensity.entries,
            listOf(R.string.danmaku_density_low, R.string.danmaku_standard, R.string.danmaku_density_high)), settings::density),
        choiceRow(R.string.danmaku_area, DanmakuSettings.AREA_OPTIONS.map {
            it to if (it == 100) text(R.string.danmaku_area_full) else context.getString(R.string.danmaku_area_top, it)
        }, settings::areaPercent),
        choiceRow(R.string.danmaku_size, labels(DanmakuSize.entries,
            listOf(R.string.subtitle_size_xsmall, R.string.subtitle_size_small, R.string.danmaku_standard, R.string.subtitle_size_large,
                R.string.subtitle_size_xlarge)), settings::size),
        choiceRow(R.string.danmaku_opacity, DanmakuSettings.OPACITY_OPTIONS.map {
            it to context.getString(R.string.danmaku_percent, it)
        }, settings::opacityPercent),
        choiceRow(R.string.danmaku_speed, labels(DanmakuSpeed.entries,
            listOf(R.string.danmaku_speed_slow, R.string.danmaku_standard, R.string.danmaku_speed_fast)), settings::speed),
        choiceRow(R.string.danmaku_stroke, labels(DanmakuStroke.entries,
            listOf(R.string.off, R.string.danmaku_stroke_thin, R.string.danmaku_standard, R.string.danmaku_stroke_thick)), settings::stroke),
        SettingsActivity.Row(text(R.string.danmaku_types), typesSummary()) { chooseTypes() },
        SettingsActivity.Row(text(R.string.danmaku_offset), offsetLabel(settings.offsetMs)) { chooseOffset() },
        toggleRow(R.string.danmaku_auto_reduce, settings::autoReduce, R.string.danmaku_auto_reduce_detail),
        SettingsActivity.Row(text(R.string.danmaku_restore_defaults), "", text(R.string.danmaku_restore_detail)) { confirmReset() },
    )

    private fun <T> labels(values: List<T>, resources: List<Int>) = values.zip(resources.map(::text))

    private fun <T> choiceRow(title: Int, options: List<Pair<T, String>>, preference: KMutableProperty0<T>) =
        SettingsActivity.Row(text(title), options.first { it.first == preference.get() }.second) {
            OptionsPanel(context, text(title), options.map { Option(it.second, selected = it.first == preference.get()) }) {
                preference.set(options[it].first)
                changed()
            }.show()
        }

    private fun toggleRow(title: Int, preference: KMutableProperty0<Boolean>, detail: Int? = null) =
        SettingsActivity.Row(text(title), onOff(preference.get()), detail?.let(::text)) {
            preference.set(!preference.get())
            changed()
        }

    private fun typeOptions() = listOf(
        R.string.danmaku_type_rolling to settings::rolling,
        R.string.danmaku_type_top to settings::top,
        R.string.danmaku_type_bottom to settings::bottom,
    )

    private fun typesSummary(): String = typeOptions().filter { it.second.get() }
        .joinToString(" · ") { text(it.first) }.ifEmpty { text(R.string.off) }

    private fun chooseTypes() {
        val types = typeOptions()
        OptionsPanel(context, text(R.string.danmaku_types), types.map { Option(text(it.first), selected = it.second.get()) },
            multi = true) { index ->
            val preference = types[index].second
            preference.set(!preference.get())
            changed()
        }.show()
    }

    private fun chooseOffset() {
        OptionsPanel(context, text(R.string.danmaku_offset), listOf(
            Option(text(R.string.danmaku_offset_adjust), offsetLabel(settings.offsetMs), selected = true),
            Option(text(R.string.danmaku_offset_reset)),
        )) { if (it == 0) adjustOffset() else { settings.offsetMs = 0; changed() } }.show()
    }

    private fun adjustOffset() {
        StepperPanel(context, text(R.string.danmaku_offset), text(R.string.danmaku_offset_hint), settings.offsetMs,
            DanmakuSettings.OFFSET_STEP_MS, DanmakuSettings.OFFSET_STEP_MS, DanmakuSettings.OFFSET_RANGE, ::offsetValue,
        ) { settings.offsetMs = it }.apply { setOnDismissListener { changed() } }.show()
    }

    private fun offsetValue(value: Long): String = if (value == 0L) text(R.string.danmaku_offset_zero)
        else context.getString(R.string.danmaku_offset_signed, value / MILLIS_PER_SECOND)

    private fun offsetLabel(value: Long): String = if (value == 0L) text(R.string.danmaku_offset_zero)
        else context.getString(if (value < 0) R.string.danmaku_offset_earlier else R.string.danmaku_offset_later,
            abs(value) / MILLIS_PER_SECOND)

    private fun confirmReset() {
        OptionsPanel(context, text(R.string.danmaku_restore_defaults), listOf(
            Option(text(R.string.cancel), selected = true), Option(text(R.string.danmaku_restore_confirm)),
        )) { if (it == 1) { settings.reset(); changed() } }.show()
    }

    private fun text(id: Int) = context.getString(id)
    private fun onOff(value: Boolean) = text(if (value) R.string.on else R.string.off)

    private companion object { const val MILLIS_PER_SECOND = 1_000.0 }
}

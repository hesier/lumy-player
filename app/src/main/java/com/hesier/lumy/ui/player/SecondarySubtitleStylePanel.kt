package com.hesier.lumy.ui.player

import android.content.Context
import android.graphics.Color
import com.hesier.lumy.R
import com.hesier.lumy.data.Settings
import com.hesier.lumy.ui.widget.Option
import com.hesier.lumy.ui.widget.OptionsPanel
import kotlin.math.roundToInt

internal class SecondarySubtitleStylePanel(
    private val context: Context,
    private val settings: Settings,
    private val onChanged: () -> Unit,
    private val onBack: () -> Unit = {},
) {
    fun show(focused: Int = 0) {
        val colors = colors()
        val colorLabel = colors.firstOrNull { it.first == settings.secondarySubtitleColor }?.second ?: R.string.subtitle_color_white
        val options = listOf(
            Option(context.getString(R.string.settings_subtitle_size), scaleLabel(settings.secondarySubtitleScale), selected = focused == 0),
            Option(context.getString(R.string.subtitle_text_color), context.getString(colorLabel), selected = focused == 1),
            Option(context.getString(R.string.settings_subtitle_background),
                context.getString(if (settings.secondarySubtitleBackground) R.string.on else R.string.off), selected = focused == 2),
        )
        OptionsPanel(context, context.getString(R.string.player_secondary_subtitle_style), options) { index ->
            when (index) {
                0 -> chooseScale()
                1 -> chooseColor()
                2 -> {
                    settings.secondarySubtitleBackground = !settings.secondarySubtitleBackground
                    onChanged()
                    show(2)
                }
            }
        }.apply { setOnCancelListener { onBack() } }.show()
    }

    private fun chooseScale() {
        val scales = listOf(0.6f, 0.7f, 0.85f, 1f, 1.15f, 1.3f)
        val options = scales.map { Option(scaleLabel(it), selected = it == settings.secondarySubtitleScale) }
        OptionsPanel(context, context.getString(R.string.settings_subtitle_size), options) {
            settings.secondarySubtitleScale = scales[it]
            onChanged()
            show(0)
        }.apply { setOnCancelListener { show(0) } }.show()
    }

    private fun chooseColor() {
        val colors = colors()
        val options = colors.map { Option(context.getString(it.second), selected = it.first == settings.secondarySubtitleColor) }
        OptionsPanel(context, context.getString(R.string.subtitle_text_color), options) {
            settings.secondarySubtitleColor = colors[it].first
            onChanged()
            show(1)
        }.apply { setOnCancelListener { show(1) } }.show()
    }

    private fun colors() = listOf(Color.WHITE to R.string.subtitle_color_white,
        Color.YELLOW to R.string.subtitle_color_yellow, Color.LTGRAY to R.string.subtitle_color_gray)

    private fun scaleLabel(scale: Float) = context.getString(R.string.secondary_subtitle_size_percent, (scale * 100).roundToInt())
}

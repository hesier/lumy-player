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
) {
    fun show() {
        val colors = colors()
        val colorLabel = colors.firstOrNull { it.first == settings.secondarySubtitleColor }?.second ?: R.string.subtitle_color_white
        val options = listOf(
            Option(context.getString(R.string.settings_subtitle_size), scaleLabel(settings.secondarySubtitleScale)),
            Option(context.getString(R.string.subtitle_text_color), context.getString(colorLabel)),
            Option(context.getString(R.string.settings_subtitle_background),
                context.getString(if (settings.secondarySubtitleBackground) R.string.on else R.string.off)),
        )
        OptionsPanel(context, context.getString(R.string.player_secondary_subtitle_style), options) { index ->
            when (index) {
                0 -> chooseScale()
                1 -> chooseColor()
                2 -> {
                    settings.secondarySubtitleBackground = !settings.secondarySubtitleBackground
                    onChanged()
                    show()
                }
            }
        }.show()
    }

    private fun chooseScale() {
        val scales = listOf(0.6f, 0.7f, 0.85f, 1f, 1.15f, 1.3f)
        val options = scales.map { Option(scaleLabel(it), selected = it == settings.secondarySubtitleScale) }
        OptionsPanel(context, context.getString(R.string.settings_subtitle_size), options) {
            settings.secondarySubtitleScale = scales[it]
            onChanged()
            show()
        }.show()
    }

    private fun chooseColor() {
        val colors = colors()
        val options = colors.map { Option(context.getString(it.second), selected = it.first == settings.secondarySubtitleColor) }
        OptionsPanel(context, context.getString(R.string.subtitle_text_color), options) {
            settings.secondarySubtitleColor = colors[it].first
            onChanged()
            show()
        }.show()
    }

    private fun colors() = listOf(Color.WHITE to R.string.subtitle_color_white,
        Color.YELLOW to R.string.subtitle_color_yellow, Color.LTGRAY to R.string.subtitle_color_gray)

    private fun scaleLabel(scale: Float) = context.getString(R.string.secondary_subtitle_size_percent, (scale * 100).roundToInt())
}

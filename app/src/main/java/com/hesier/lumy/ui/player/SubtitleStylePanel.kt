package com.hesier.lumy.ui.player

import android.content.Context
import com.hesier.lumy.R
import com.hesier.lumy.data.Settings
import com.hesier.lumy.ui.widget.Option
import com.hesier.lumy.ui.widget.OptionsPanel

internal class SubtitleStylePanel(
    private val context: Context,
    private val settings: Settings,
    private val onChanged: () -> Unit,
    private val onBack: () -> Unit,
    private val onSecondaryStyle: (() -> Unit)? = null,
    private val allowPositionAndBackground: Boolean = true,
    private val allowScale: Boolean = true,
) {
    fun show(focused: Int = 0) {
        val rows = rows()
        val initial = focused.coerceIn(0, rows.lastIndex.coerceAtLeast(0))
        val options = rows.mapIndexed { index, row -> row.first.copy(selected = index == initial) }
        OptionsPanel(context, context.getString(R.string.player_subtitle_style), options,
            hint = hint()) { index -> rows[index].second(index) }
            .apply { setOnCancelListener { onBack() } }.show()
    }

    private fun rows(): List<Pair<Option, (Int) -> Unit>> = buildList {
        if (allowScale) {
            add(Option(context.getString(R.string.settings_subtitle_size),
                context.getString(scaleLabels[scaleIndex()])) to ::chooseScale)
        }
        if (allowPositionAndBackground) {
            add(Option(context.getString(R.string.settings_subtitle_position),
                context.getString(positionLabels[settings.subtitlePosition])) to ::choosePosition)
            add(Option(context.getString(R.string.settings_subtitle_background),
                context.getString(if (settings.subtitleBackground) R.string.on else R.string.off)) to { index ->
                settings.subtitleBackground = !settings.subtitleBackground
                onChanged()
                show(index)
            })
        }
        onSecondaryStyle?.let { action ->
            add(Option(context.getString(R.string.player_secondary_subtitle_style)) to { _ -> action() })
        }
        if (isEmpty()) {
            add(Option(context.getString(R.string.player_subtitles)) to { _ -> onBack() })
        }
    }

    private fun chooseScale(focused: Int) {
        val options = scaleLabels.mapIndexed { index, label ->
            Option(context.getString(label), selected = index == scaleIndex())
        }
        OptionsPanel(context, context.getString(R.string.settings_subtitle_size), options) { index ->
            settings.subtitleScale = Settings.SUBTITLE_SCALES[index]
            onChanged()
            show(focused)
        }.apply { setOnCancelListener { show(focused) } }.show()
    }

    private fun choosePosition(focused: Int) {
        val options = Settings.SUBTITLE_POSITIONS.indices.map { index ->
            Option(context.getString(positionLabels[index]), selected = index == settings.subtitlePosition)
        }
        OptionsPanel(context, context.getString(R.string.settings_subtitle_position), options,
            hint = context.getString(R.string.settings_subtitle_position_detail)) { index ->
            settings.subtitlePosition = index
            onChanged()
            show(focused)
        }.apply { setOnCancelListener { show(focused) } }.show()
    }

    private fun scaleIndex() = Settings.SUBTITLE_SCALES.indexOf(settings.subtitleScale)
        .takeIf { it >= 0 } ?: Settings.SUBTITLE_SCALES.indexOf(1f)

    private fun hint() = context.getString(
        if (!allowScale && !allowPositionAndBackground) R.string.player_subtitle_style_unavailable
        else R.string.player_style_saved_hint,
    )

    private companion object {
        val scaleLabels = listOf(R.string.subtitle_size_xsmall, R.string.subtitle_size_small,
            R.string.subtitle_size_normal, R.string.subtitle_size_large, R.string.subtitle_size_xlarge)
        val positionLabels = listOf(R.string.subtitle_position_low, R.string.subtitle_position_normal,
            R.string.subtitle_position_high, R.string.subtitle_position_higher)
    }
}

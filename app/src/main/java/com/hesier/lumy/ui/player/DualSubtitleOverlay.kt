@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.hesier.lumy.data.Settings
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.widget.AssSubtitleView

/** Compose ordinary text together so wrapped primary lines always stay above the secondary. */
internal class DualSubtitleOverlay(private val playerView: PlayerView, private val settings: Settings, ass: AssHandler) : Player.Listener {
    private val overlay = SubtitleView(playerView.context).apply {
        setStyle(CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null))
        setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.1f * settings.subtitleScale)
        setBottomPaddingFraction(Settings.SUBTITLE_POSITIONS[settings.subtitlePosition])
    }
    private var primary = emptyList<Cue>()
    private var secondary = emptyList<Cue>()
    private val bitmap = BitmapSubtitleOverlay(playerView, overlay)

    init {
        (playerView.subtitleView?.parent as ViewGroup).addView(overlay,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.addView(AssSubtitleView(playerView.context, ass))
    }

    override fun onCues(cueGroup: CueGroup) {
        primary = cueGroup.cues
        update()
    }

    fun onSecondaryCues(cueGroup: CueGroup) {
        secondary = cueGroup.cues
        bitmap.onCues(cueGroup)
        update()
    }

    fun setBottomPaddingFraction(fraction: Float) = overlay.setBottomPaddingFraction(fraction)

    fun refreshStyle() = update()

    fun clear() {
        primary = emptyList()
        secondary = emptyList()
        bitmap.clear()
        overlay.setCues(emptyList())
    }

    private fun update() {
        if (secondary.none { it.text != null }) {
            overlay.setCues(emptyList())
            playerView.subtitleView?.setCues(primary)
            return
        }
        val text = SpannableStringBuilder()
        val primaryText = primary.filter { it.text != null }
        primaryText.forEach { cue ->
            if (text.isNotEmpty()) text.append('\n')
            text.append(cue.text)
        }
        if (settings.subtitleBackground && text.isNotEmpty()) {
            text.setSpan(BackgroundColorSpan(BACKGROUND_COLOR), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (text.isNotEmpty()) text.append('\n')
        val secondaryStart = text.length
        secondary.mapNotNull { it.text }.forEach { line ->
            if (text.length > secondaryStart) text.append('\n')
            text.append(line)
        }
        text.setSpan(RelativeSizeSpan(settings.secondarySubtitleScale), secondaryStart, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(settings.secondarySubtitleColor), secondaryStart, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (settings.secondarySubtitleBackground) {
            text.setSpan(BackgroundColorSpan(BACKGROUND_COLOR), secondaryStart, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (primaryText.isNotEmpty()) playerView.subtitleView?.setCues(primary.filter { it.text == null })
        overlay.setCues(if (text.isEmpty()) emptyList() else listOf(Cue.Builder().setText(text).build()))
    }

    private companion object {
        const val BACKGROUND_COLOR = 0x99000000.toInt()
    }
}

package com.hesier.lumy.ui.player

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.hesier.lumy.util.DebugLog
import kotlin.math.roundToInt

/** Bitmap subtitles retain their authored canvas even when the video has cropped letterboxing. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class BitmapSubtitleOverlay(private val playerView: PlayerView) : Player.Listener {
    private val normal = playerView.subtitleView
    private val overlay = SubtitleView(playerView.context).apply { visibility = View.GONE }
    private var aspectRatio: Float? = null

    init {
        playerView.addView(overlay, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        playerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateBounds() }
    }

    override fun onCues(cueGroup: CueGroup) {
        val cues = cueGroup.cues
        val aspect = cues.takeIf { it.isNotEmpty() && it.all { cue -> cue.bitmap != null } }
            ?.firstNotNullOfOrNull(::canvasAspectRatio)
        if (aspect == null) {
            showNormal()
            return
        }
        aspectRatio = aspect
        normal?.visibility = View.INVISIBLE
        updateBounds()
        overlay.setCues(cues)
        overlay.visibility = View.VISIBLE
    }

    fun clear() {
        showNormal()
        normal?.setCues(emptyList())
    }

    private fun showNormal() {
        aspectRatio = null
        overlay.setCues(emptyList())
        overlay.visibility = View.GONE
        normal?.visibility = View.VISIBLE
    }

    private fun updateBounds() {
        val aspect = aspectRatio ?: return
        val maxWidth = playerView.width
        val maxHeight = playerView.height
        if (maxWidth <= 0 || maxHeight <= 0) return
        val width = minOf(maxWidth, (maxHeight * aspect).roundToInt()).coerceAtLeast(1)
        val height = (width / aspect).roundToInt().coerceIn(1, maxHeight)
        val params = overlay.layoutParams
        if (params.width == width && params.height == height) return
        params.width = width
        params.height = height
        overlay.layoutParams = params
        DebugLog.i("LumyPlayer", "bitmap subtitle canvas=${width}x$height aspect=$aspect")
    }

    private fun canvasAspectRatio(cue: Cue): Float? {
        val bitmap = cue.bitmap ?: return null
        if (!cue.size.isFinite() || cue.size <= 0f || !cue.bitmapHeight.isFinite() || cue.bitmapHeight <= 0f) return null
        // PGS stores normalized bitmap bounds, so recover its canvas instead of assuming 16:9.
        val aspect = (bitmap.width.toDouble() * cue.bitmapHeight / (bitmap.height.toDouble() * cue.size)).toFloat()
        return aspect.takeIf { it.isFinite() && it > 0f }
    }
}

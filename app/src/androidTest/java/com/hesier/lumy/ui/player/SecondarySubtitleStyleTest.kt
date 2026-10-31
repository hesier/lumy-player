@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.ViewGroup
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.hesier.lumy.data.Settings
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.type.AssRenderType

class SecondarySubtitleStyleTest {
    fun runAll(context: Context) {
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("secondary-subtitle-style-test", mode)
        }
        val settings = Settings(isolated).apply {
            subtitleBackground = false
            subtitleScale = 1f
            secondarySubtitleScale = 0.85f
            secondarySubtitleColor = Color.WHITE
            secondarySubtitleBackground = false
        }
        val view = PlayerView(context)
        val ass = AssHandler(AssRenderType.OVERLAY_OPEN_GL, AssHandlerConfig())
        try {
            val overlay = DualSubtitleOverlay(view, settings, ass)
            overlay.onCues(group(Cue.Builder().setText("PRIMARY").build()))
            overlay.onSecondaryCues(group(Cue.Builder().setText("SECONDARY").build()))
            val parent = view.subtitleView!!.parent as ViewGroup
            val output = (0 until parent.childCount).map { parent.getChildAt(it) }.filterIsInstance<SubtitleView>().last()
            checkStyle(cues(output).single().text as Spanned, 0.85f, Color.WHITE, false)
            settings.secondarySubtitleScale = 0.6f
            settings.secondarySubtitleColor = Color.YELLOW
            settings.secondarySubtitleBackground = true
            overlay.refreshStyle()
            checkStyle(cues(output).single().text as Spanned, 0.6f, Color.YELLOW, true)
            val saved = Settings(isolated)
            check(saved.secondarySubtitleScale == 0.6f && saved.secondarySubtitleColor == Color.YELLOW && saved.secondarySubtitleBackground)
            check(saved.subtitleScale == 1f && !saved.subtitleBackground)
            checkBitmap(view, overlay, output)
            overlay.onSecondaryCues(CueGroup(emptyList(), 0))
            check(cues(output).isEmpty() && cues(view.subtitleView!!).single().text.toString() == "PRIMARY")
        } finally { ass.release() }
    }

    private fun checkStyle(text: Spanned, size: Float, color: Int, background: Boolean) {
        check(text.toString() == "PRIMARY\nSECONDARY")
        val scale = text.getSpans(8, text.length, RelativeSizeSpan::class.java).single()
        val foreground = text.getSpans(8, text.length, ForegroundColorSpan::class.java).single()
        check(scale.sizeChange == size && foreground.foregroundColor == color)
        check(text.getSpanStart(scale) == 8 && text.getSpanStart(foreground) == 8)
        check(text.getSpans(8, text.length, BackgroundColorSpan::class.java).isNotEmpty() == background)
        check(text.getSpans(0, 7, BackgroundColorSpan::class.java).isEmpty())
    }

    private fun checkBitmap(view: PlayerView, overlay: DualSubtitleOverlay, textOutput: SubtitleView) {
        val bitmap = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        val cue = Cue.Builder().setBitmap(bitmap).setPosition(0.2f).setLine(0.7f, Cue.LINE_TYPE_FRACTION)
            .setSize(0.1f).setBitmapHeight(0.05f).build()
        overlay.onSecondaryCues(group(cue))
        check(cues(textOutput).isEmpty())
        val output = (0 until view.childCount).map { view.getChildAt(it) }.filterIsInstance<SubtitleView>().last()
        val rendered = cues(output).single()
        check(rendered.bitmap === bitmap && rendered.line == 0.7f && rendered.position == 0.2f)
    }

    @Suppress("UNCHECKED_CAST")
    private fun cues(view: SubtitleView): List<Cue> = SubtitleView::class.java.getDeclaredField("cues")
        .apply { isAccessible = true }.get(view) as List<Cue>

    private fun group(cue: Cue) = CueGroup(listOf(cue), 0)
}

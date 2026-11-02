package com.hesier.lumy.ui.player

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.render.DanmakuRenderer
import com.kuaishou.akdanmaku.ui.DanmakuDisplayer
import com.kuaishou.akdanmaku.utils.Size
import kotlin.math.ceil

/** 测量和缓存线程独享画笔，回退到 UI 绘制时也不会互相改写状态。 */
internal class DanmakuTextRenderer(private val outlineDp: Float) : DanmakuRenderer {
    private class Paints {
        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
        val outline = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            style = Paint.Style.STROKE
            color = Color.BLACK
            strokeJoin = Paint.Join.ROUND
        }
    }
    private val local = object : ThreadLocal<Paints>() {
        override fun initialValue() = Paints()
    }

    override fun updatePaint(item: DanmakuItem, displayer: DanmakuDisplayer, config: DanmakuConfig) {
        val paints = local.get()!!
        val size = item.data.textSize * displayer.scaleDensity * config.textSizeScale
        paints.fill.textSize = size
        paints.fill.color = item.data.textColor or Color.BLACK
        paints.outline.textSize = size
        paints.outline.strokeWidth = outlineDp * displayer.density
    }

    override fun measure(item: DanmakuItem, displayer: DanmakuDisplayer, config: DanmakuConfig): Size {
        updatePaint(item, displayer, config)
        val paint = local.get()!!.fill
        val metrics = paint.fontMetrics
        val padding = padding(displayer)
        return Size(ceil(paint.measureText(item.data.content) + padding * 2).toInt(),
            ceil(metrics.descent - metrics.ascent + padding * 2).toInt())
    }

    override fun draw(item: DanmakuItem, canvas: Canvas, displayer: DanmakuDisplayer, config: DanmakuConfig) {
        updatePaint(item, displayer, config)
        val paints = local.get()!!
        val x = padding(displayer)
        val y = x - paints.fill.ascent()
        if (outlineDp > 0) canvas.drawText(item.data.content, x, y, paints.outline)
        canvas.drawText(item.data.content, x, y, paints.fill)
    }

    private fun padding(displayer: DanmakuDisplayer) = (PADDING_DP + outlineDp) * displayer.density
    private companion object { const val PADDING_DP = 2f }
}

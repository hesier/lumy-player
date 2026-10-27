package com.hesier.lumy.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.hesier.lumy.R

/**
 * 卡片底部的观看进度条。ProgressBar 每次 inflate 都要加载一整套不定进度的动画资源，
 * 一行十几张卡片在弱 CPU 上要多花几十毫秒，这里只画两个矩形。
 */
class ProgressLine @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val track = Paint().apply { color = 0x66000000 }
    private val fill = Paint().apply { color = ContextCompat.getColor(context, R.color.accent) }

    /** 0 到 1 */
    var progress: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v == field) return
            field = v
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val split = w * progress
        canvas.drawRect(split, 0f, w, h, track)
        canvas.drawRect(0f, 0f, split, h, fill)
    }
}

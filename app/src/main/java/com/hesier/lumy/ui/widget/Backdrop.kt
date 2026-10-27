package com.hesier.lumy.ui.widget

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import androidx.core.content.ContextCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.hesier.lumy.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 背景图 + 左侧、底部渐变遮罩预先合成成一张图，作为窗口背景。
 * 分开叠放时每帧要画 4 层全屏（窗口底色、背景图、两层遮罩），投影仪这类弱 GPU 上下滚动会掉帧；
 * 合成后只画 1 层。
 */
object Backdrop {

    private const val ALPHA = 0.6f
    private const val FADE_MS = 180

    suspend fun show(activity: Activity, url: String) {
        val dm = activity.resources.displayMetrics
        val decor = activity.window.decorView
        val width = decor.width.takeIf { it > 0 } ?: dm.widthPixels
        val height = decor.height.takeIf { it > 0 } ?: dm.heightPixels
        val request = ImageRequest.Builder(activity)
            .data(url)
            .size(width, height)
            // 要在软件 Canvas 上读像素，不能是硬件位图
            .allowHardware(false)
            .build()
        val result = activity.imageLoader.execute(request) as? SuccessResult ?: return
        val source = (result.drawable as? BitmapDrawable)?.bitmap ?: return
        val composed = withContext(Dispatchers.Default) { compose(activity, source, width, height) }
        if (activity.isFinishing || activity.isDestroyed) return
        val bg = ColorDrawable(ContextCompat.getColor(activity, R.color.bg))
        // 淡入结束后 TransitionDrawable 只画第二层（需要 crossFade），仍然是一层
        val fade = TransitionDrawable(arrayOf<Drawable>(bg, BitmapDrawable(activity.resources, composed)))
        fade.isCrossFadeEnabled = true
        activity.window.setBackgroundDrawable(fade)
        fade.startTransition(FADE_MS)
    }

    private fun compose(activity: Activity, source: Bitmap, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(ContextCompat.getColor(activity, R.color.bg))
        // centerCrop
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val srcW = (width / scale).toInt()
        val srcH = (height / scale).toInt()
        val left = (source.width - srcW) / 2
        val top = (source.height - srcH) / 2
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (ALPHA * 255).toInt() }
        canvas.drawBitmap(source, Rect(left, top, left + srcW, top + srcH), Rect(0, 0, width, height), paint)
        for (scrim in intArrayOf(R.drawable.scrim_left, R.drawable.scrim_bottom)) {
            ContextCompat.getDrawable(activity, scrim)?.apply { setBounds(0, 0, width, height) }?.draw(canvas)
        }
        return out
    }
}

package com.hesier.lumy.ui.widget

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.widget.ImageView
import androidx.core.content.ContextCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.hesier.lumy.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 背景图 + 左侧、底部渐变遮罩预先合成成一张图。
 * 分开叠放时每帧要画好几层（窗口底色、背景图、两层遮罩），投影仪这类弱 GPU 上滚动会掉帧；
 * 合成后只画 1 层。
 */
object Backdrop {

    /** 详情页：铺满整个窗口，作为窗口背景 */
    suspend fun showAsWindowBackground(activity: Activity, url: String) {
        val dm = activity.resources.displayMetrics
        val decor = activity.window.decorView
        val width = decor.width.takeIf { it > 0 } ?: dm.widthPixels
        val height = decor.height.takeIf { it > 0 } ?: dm.heightPixels
        val composed = compose(activity, url, width, height, alpha = 0.6f) ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val bg = ColorDrawable(ContextCompat.getColor(activity, R.color.bg))
        activity.window.setBackgroundDrawable(crossfade(bg, BitmapDrawable(activity.resources, composed), 180))
    }

    /** 首页右上角：放进 [view]（尺寸已固定），和上一张交叉淡入 */
    suspend fun showIn(view: ImageView, url: String, alpha: Float) {
        val w = view.width.takeIf { it > 0 } ?: return
        val h = view.height.takeIf { it > 0 } ?: return
        val composed = compose(view.context, url, w, h, alpha) ?: return
        val previous = when (val d = view.drawable) {
            // 上一次的淡入已经结束，只取它的终点，避免一层层嵌套
            is TransitionDrawable -> d.getDrawable(d.numberOfLayers - 1)
            null -> ColorDrawable(ContextCompat.getColor(view.context, R.color.bg))
            else -> d
        }
        view.setImageDrawable(crossfade(previous, BitmapDrawable(view.resources, composed), 400))
    }

    /** 淡入结束后 TransitionDrawable 只画第二层（需要 crossFade），仍然只有一层 */
    private fun crossfade(from: Drawable, to: Drawable, ms: Int): TransitionDrawable =
        TransitionDrawable(arrayOf(from, to)).apply {
            isCrossFadeEnabled = true
            startTransition(ms)
        }

    private suspend fun compose(context: Context, url: String, width: Int, height: Int, alpha: Float): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(width, height)
            // 要在软件 Canvas 上读像素，不能是硬件位图
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
        val source = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
        return withContext(Dispatchers.Default) { draw(context, source, width, height, alpha) }
    }

    private fun draw(context: Context, source: Bitmap, width: Int, height: Int, alpha: Float): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(ContextCompat.getColor(context, R.color.bg))
        // centerCrop
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val srcW = (width / scale).toInt()
        val srcH = (height / scale).toInt()
        val left = (source.width - srcW) / 2
        val top = (source.height - srcH) / 2
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { this.alpha = (alpha * 255).toInt() }
        canvas.drawBitmap(source, Rect(left, top, left + srcW, top + srcH), Rect(0, 0, width, height), paint)
        for (scrim in intArrayOf(R.drawable.scrim_left, R.drawable.scrim_bottom)) {
            ContextCompat.getDrawable(context, scrim)?.apply { setBounds(0, 0, width, height) }?.draw(canvas)
        }
        return out
    }
}

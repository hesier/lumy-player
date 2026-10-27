package com.hesier.lumy.ui.widget

import android.view.View
import android.view.ViewOutlineProvider
import com.hesier.lumy.databinding.ViewStateBinding
import kotlinx.coroutines.CancellationException

private const val FOCUS_SCALE = 1.08f

/** 获得焦点时放大，TV 上用户靠这个判断当前位置 */
fun View.scaleOnFocus(scale: Float = FOCUS_SCALE) = scaleOnFocus(this, scale)

/**
 * 焦点在 [this] 上时放大 [target]。列表卡片要放大内部的图片区域而不是卡片本身：
 * RecyclerView 的条目动画器会对卡片根 View 调用 animate().cancel()，放大动画会被打断。
 */
fun View.scaleOnFocus(target: View, scale: Float = FOCUS_SCALE) {
    setOnFocusChangeListener { v, hasFocus ->
        val s = if (hasFocus) scale else 1f
        target.animate().scaleX(s).scaleY(s).setDuration(150).start()
        // 放大后盖住相邻卡片
        v.translationZ = if (hasFocus) 1f else 0f
    }
}

/** 重新绑定或复用卡片时，按当前焦点状态直接设定放大比例（不做动画） */
fun View.syncFocusScale(target: View, scale: Float = FOCUS_SCALE) {
    val s = if (hasFocus()) scale else 1f
    target.animate().cancel()
    target.scaleX = s
    target.scaleY = s
    translationZ = if (hasFocus()) 1f else 0f
}

/** 按背景的圆角裁剪子 View（图片），API 21 起可用 */
fun View.clipToRoundedBackground() {
    outlineProvider = ViewOutlineProvider.BACKGROUND
    clipToOutline = true
}

fun ViewStateBinding.showLoading() {
    root.visibility = View.VISIBLE
    progress.visibility = View.VISIBLE
    message.visibility = View.GONE
    action.visibility = View.GONE
}

fun ViewStateBinding.showMessage(text: CharSequence, actionLabel: CharSequence? = null, onAction: (() -> Unit)? = null) {
    root.visibility = View.VISIBLE
    progress.visibility = View.GONE
    message.visibility = View.VISIBLE
    message.text = text
    if (actionLabel != null && onAction != null) {
        action.visibility = View.VISIBLE
        action.text = actionLabel
        action.setOnClickListener { onAction() }
        action.requestFocus()
    } else {
        action.visibility = View.GONE
    }
}

fun ViewStateBinding.hide() {
    root.visibility = View.GONE
}

/** 像 runCatching，但不吞掉协程取消 */
suspend inline fun <T> attempt(crossinline block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

fun Throwable.readableMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

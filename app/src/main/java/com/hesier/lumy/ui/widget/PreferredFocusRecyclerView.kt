package com.hesier.lumy.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * 从外面移进这一行时，焦点直接落到 [preferredPosition] 指定的那一项（当前季 / 当前集），
 * 而不是几何上最近的那一项。只给焦点查找提供这一个候选，避免先落到别处再跳过去的闪动。
 */
class PreferredFocusRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RecyclerView(context, attrs) {

    var preferredPosition: () -> Int = { -1 }

    /** 为 true 时上下键不离开这个列表，到头就停住（避免斜着跳到旁边的列表） */
    var keepVerticalFocus = false

    override fun focusSearch(focused: View, direction: Int): View? {
        val next = super.focusSearch(focused, direction)
        if (!keepVerticalFocus || (direction != FOCUS_UP && direction != FOCUS_DOWN)) return next
        return if (next != null && isInside(next)) next else focused
    }

    private fun isInside(view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === this) return true
            v = v.parent as? View
        }
        return false
    }

    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        if (!hasFocus()) {
            val target = findViewHolderForAdapterPosition(preferredPosition())?.itemView
            if (target != null && target.isFocusable) {
                views.add(target)
                return
            }
        }
        super.addFocusables(views, direction, focusableMode)
    }

    /**
     * 整行刚滚进屏幕时，系统不经过 [addFocusables]，而是让这一行“自己挑一个子项”接收焦点，
     * 这里同样交给 [preferredPosition]。
     */
    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: android.graphics.Rect?): Boolean {
        val target = findViewHolderForAdapterPosition(preferredPosition())?.itemView
        if (target != null && target.requestFocus(direction, previouslyFocusedRect)) return true
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }
}

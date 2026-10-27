package com.hesier.lumy.ui.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/**
 * 一排按钮：从外面移进来时回到上次停留的那个按钮（没停留过就是第一个），
 * 而不是按几何位置找最近的（从很宽的简介或下面的演员行进来常会落到最后一个）。
 */
class FocusMemoryLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private var lastFocused = 0

    private val target: View?
        get() = getChildAt(lastFocused.coerceAtMost(childCount - 1))?.takeIf { it.isFocusable && it.visibility == VISIBLE }

    override fun requestChildFocus(child: View, focused: View) {
        super.requestChildFocus(child, focused)
        lastFocused = indexOfChild(child)
    }

    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        val t = target
        if (!hasFocus() && t != null) {
            views.add(t)
            return
        }
        super.addFocusables(views, direction, focusableMode)
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean =
        target?.requestFocus(direction, previouslyFocusedRect) == true ||
            super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
}

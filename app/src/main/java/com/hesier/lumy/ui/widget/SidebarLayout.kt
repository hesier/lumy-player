package com.hesier.lumy.ui.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import com.hesier.lumy.R

/** Keeps labels measured at their expanded width while revealing only the visible rail. */
class SidebarLayout(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val revealClip = Rect()

    var revealedWidth: Int = resources.getDimensionPixelSize(R.dimen.sidebar_collapsed)
        set(value) {
            field = value
            updateClip()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateClip()
    }

    private fun updateClip() {
        revealClip.set(0, 0, revealedWidth, height)
        clipBounds = revealClip
    }

    private fun containsPointer(event: MotionEvent): Boolean =
        event.x >= 0 && event.x < revealedWidth && event.y >= 0 && event.y < height

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Clip bounds affect rendering, but do not limit child hit testing.
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !containsPointer(event)) return false
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (containsPointer(event)) return super.dispatchHoverEvent(event)
        val exit = MotionEvent.obtain(event)
        exit.action = MotionEvent.ACTION_HOVER_EXIT
        super.dispatchHoverEvent(exit)
        exit.recycle()
        return false
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        containsPointer(event) && super.dispatchGenericMotionEvent(event)
}

/** FocusFinder must see the visible row rather than its permanently expanded layout. */
class SidebarItemLayout(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val visibleRect = Rect()

    override fun getDrawingRect(rect: Rect) {
        super.getDrawingRect(rect)
        var ancestor = parent
        while (ancestor is View) {
            if (ancestor is SidebarLayout) {
                visibleRect.set(0, 0, ancestor.revealedWidth - ancestor.paddingRight, ancestor.height)
                ancestor.offsetRectIntoDescendantCoords(this, visibleRect)
                rect.right = minOf(rect.right, visibleRect.right).coerceAtLeast(rect.left)
                return
            }
            ancestor = ancestor.parent
        }
    }
}

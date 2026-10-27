package com.hesier.lumy.ui.widget

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 首页、详情页各行共用的布局：
 * - 纵向列表：获得焦点的行对齐到顶部；
 * - 横向行：焦点在可见卡片间移动时不滚动，只有下一张要出屏时才滚到刚好露出，
 *   不会每按一次整行都滑动；左右移动只在本行内进行，不会跳到别的行。
 */
class StartAlignedLayoutManager(
    context: Context,
    orientation: Int,
    /** 行首按左也停在本行（页面左边没有侧边栏等可去的地方时，避免跳到上下行） */
    private val stayAtStart: Boolean = false,
) : LinearLayoutManager(context, orientation, false) {

    private var recyclerView: RecyclerView? = null

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        recyclerView = view
    }

    override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
        super.onDetachedFromWindow(view, recycler)
        recyclerView = null
    }

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        if (orientation != HORIZONTAL) return null
        if (direction != View.FOCUS_LEFT && direction != View.FOCUS_RIGHT) return null
        val item = findContainingItemView(focused) ?: return null
        val position = getPosition(item)
        val next = if (direction == View.FOCUS_RIGHT) position + 1 else position - 1
        if (next >= itemCount) return focused
        if (next < 0) return if (stayAtStart) focused else leaveRow(focused, direction)
        val target = findViewByPosition(next)
        if (target != null) return target
        // 下一张还没布局出来（长按时常见）：先滚过去，焦点原地不动
        scrollToPosition(next)
        return focused
    }

    /**
     * 行首按左：只允许离开到“行”以外的地方（侧边栏）。
     * 系统按几何位置找最近的，下一行如果滚动过、有卡片露在左边，就会被选中，这里排除掉。
     */
    private fun leaveRow(focused: View, direction: Int): View? {
        val root = focused.rootView as? ViewGroup ?: return focused
        val found = FocusFinder.getInstance().findNextFocus(root, focused, direction) ?: return focused
        var parent = found.parent
        while (parent is View) {
            if (parent is RecyclerView && parent !== recyclerView &&
                (parent.layoutManager as? LinearLayoutManager)?.orientation == HORIZONTAL
            ) {
                return focused
            }
            parent = parent.parent
        }
        return found
    }

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean,
    ): Boolean {
        val delta = if (orientation == HORIZONTAL) {
            // 只在卡片超出可见范围时滚动，滚到刚好完整露出（留一点边距给放大效果）
            val margin = (EDGE_MARGIN_DP * parent.resources.displayMetrics.density).toInt()
            val left = getDecoratedLeft(child) - paddingLeft
            val right = getDecoratedRight(child) - (width - paddingRight)
            when {
                left < 0 -> left - if (getPosition(child) == 0) 0 else margin
                right > 0 -> right + margin
                else -> 0
            }
        } else {
            // 横向行里左右移动时，这个请求也会冒泡到纵向列表。最后几行无法滚到顶部对齐，
            // 每次都去“再滚一点”会造成页面跳动：只滚到实际能滚到的位置，滚不动就什么也不做
            val wanted = getDecoratedTop(child) - paddingTop
            when {
                wanted > 0 && !parent.canScrollVertically(1) -> 0
                wanted < 0 && !parent.canScrollVertically(-1) -> 0
                else -> wanted
            }
        }
        if (delta == 0) return false
        val dx = if (orientation == HORIZONTAL) delta else 0
        val dy = if (orientation == VERTICAL) delta else 0
        if (immediate) parent.scrollBy(dx, dy) else parent.smoothScrollBy(dx, dy)
        return true
    }

    private companion object {
        const val EDGE_MARGIN_DP = 24
    }
}

/**
 * 长按方向键时系统每秒发 20 多次按键，焦点飞快移动、列表来不及布局。
 * 按住不放时限制为每 [intervalMs] 毫秒移动一格，单次按下不受影响。
 */
class KeyRepeatThrottle(private val intervalMs: Long = 160) {
    private var last = 0L

    /** @return true 表示这次按键应该丢弃 */
    fun shouldDrop(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.keyCode !in DIRECTION_KEYS) return false
        val now = SystemClock.uptimeMillis()
        if (event.repeatCount > 0 && now - last < intervalMs) return true
        last = now
        return false
    }

    private companion object {
        val DIRECTION_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        )
    }
}

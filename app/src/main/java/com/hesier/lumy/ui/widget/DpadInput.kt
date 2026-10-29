package com.hesier.lumy.ui.widget

import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** 键盘收起时上下切换控件，左右先移动光标，到文字边界再离开输入框。 */
fun EditText.enableDpadNavigation(targetForDirection: (Int) -> View?) {
    setOnKeyListener { _, keyCode, event ->
        if (event.action != KeyEvent.ACTION_DOWN || !event.hasNoModifiers() ||
            ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        ) return@setOnKeyListener false
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            else -> return@setOnKeyListener false
        }
        val horizontal = direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT
        if (horizontal) {
            if (selectionStart != selectionEnd) return@setOnKeyListener false
            val edge = if (direction == View.FOCUS_LEFT) 0 else text.length
            if (selectionStart != edge) return@setOnKeyListener false
        }
        targetForDirection(direction)?.requestFocus(direction)
        true
    }
}

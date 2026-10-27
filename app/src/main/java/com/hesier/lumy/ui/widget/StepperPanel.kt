package com.hesier.lumy.ui.widget

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.WindowManager
import com.hesier.lumy.R
import com.hesier.lumy.databinding.ViewStepperBinding

/** 右侧数值调节面板，改动实时回调，适合遥控器微调 */
class StepperPanel(
    context: Context,
    private val title: String,
    private val hint: String,
    initial: Long,
    private val smallStep: Long,
    private val bigStep: Long,
    private val range: LongRange,
    private val format: (Long) -> String,
    private val onChange: (Long) -> Unit,
) : Dialog(context, R.style.Theme_Lumy_Panel) {

    private var value = initial
    private lateinit var b: ViewStepperBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ViewStepperBinding.inflate(LayoutInflater.from(context))
        setContentView(b.root)
        window?.apply {
            setLayout((360 * context.resources.displayMetrics.density).toInt(), WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END)
        }
        b.title.text = title
        b.hint.text = hint
        render()
        b.decrease.setOnClickListener { change(-smallStep) }
        b.increase.setOnClickListener { change(smallStep) }
        b.root.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -smallStep
            KeyEvent.KEYCODE_DPAD_RIGHT -> smallStep
            KeyEvent.KEYCODE_DPAD_DOWN -> -bigStep
            KeyEvent.KEYCODE_DPAD_UP -> bigStep
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                dismiss()
                return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        change(delta)
        return true
    }

    private fun change(delta: Long) {
        value = (value + delta).coerceIn(range)
        render()
        onChange(value)
    }

    private fun render() {
        b.value.text = format(value)
    }
}

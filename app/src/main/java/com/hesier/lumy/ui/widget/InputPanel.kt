package com.hesier.lumy.ui.widget

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import com.hesier.lumy.R
import com.hesier.lumy.databinding.ViewInputPanelBinding

/**
 * 右侧弹出的单行输入面板。焦点先落在输入框上（确认键弹出键盘），
 * 键盘上的“完成”或下方的按钮都会提交，返回键取消。
 */
class InputPanel(
    context: Context,
    private val title: String,
    private val initial: String,
    private val hint: String,
    private val actionLabel: String,
    private val onSubmit: (String) -> Unit,
) : Dialog(context, R.style.Theme_Lumy_Panel) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ViewInputPanelBinding.inflate(LayoutInflater.from(context))
        setContentView(b.root)
        window?.apply {
            setLayout((PANEL_WIDTH_DP * context.resources.displayMetrics.density).toInt(), WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END)
            // 进入时不自动弹出键盘，和登录页一致
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        }
        b.title.text = title
        b.input.setText(initial)
        b.input.setSelection(initial.length)
        b.input.hint = hint
        b.save.text = actionLabel
        val submit = {
            dismiss()
            onSubmit(b.input.text.toString())
        }
        b.input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) submit()
            false
        }
        b.save.setOnClickListener { submit() }
        b.input.requestFocus()
    }

    private companion object {
        const val PANEL_WIDTH_DP = 420
    }
}

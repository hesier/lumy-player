package com.hesier.lumy.ui.widget

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import com.hesier.lumy.R
import com.hesier.lumy.databinding.ViewTextPanelBinding

/** 右侧弹出的长文本面板（完整简介），上下键滚动，返回键关闭 */
class TextPanel(context: Context, private val title: String, private val text: CharSequence) :
    Dialog(context, R.style.Theme_Lumy_Panel) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ViewTextPanelBinding.inflate(LayoutInflater.from(context))
        setContentView(b.root)
        window?.apply {
            setLayout((640 * context.resources.displayMetrics.density).toInt(), WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END)
        }
        b.title.text = title
        b.text.text = text
        b.scroll.requestFocus()
    }
}

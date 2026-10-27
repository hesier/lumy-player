package com.hesier.lumy.ui.widget

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.R
import com.hesier.lumy.databinding.ItemOptionBinding
import com.hesier.lumy.databinding.ViewOptionsPanelBinding

data class Option(val label: String, val detail: String? = null, val selected: Boolean = false)

/**
 * 从右侧滑出的选项面板。单选：选中后自动关闭；
 * 多选（[multi]）：确认键切换勾选，面板保持打开，返回键关闭。
 */
class OptionsPanel(
    context: Context,
    private val title: String,
    options: List<Option>,
    private val multi: Boolean = false,
    private val onPick: (Int) -> Unit,
) : Dialog(context, R.style.Theme_Lumy_Panel) {

    private val options = options.toMutableList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ViewOptionsPanelBinding.inflate(LayoutInflater.from(context))
        setContentView(b.root)
        window?.apply {
            setLayout((PANEL_WIDTH_DP * context.resources.displayMetrics.density).toInt(), WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END)
        }
        b.title.text = title
        b.list.layoutManager = LinearLayoutManager(context)
        b.list.adapter = Adapter()
        val initial = options.indexOfFirst { it.selected }.coerceAtLeast(0)
        b.list.scrollToPosition(initial)
        b.list.post { b.list.findViewHolderForAdapterPosition(initial)?.itemView?.requestFocus() }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        override fun getItemCount() = options.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemOptionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val o = options[position]
            holder.b.label.text = o.label
            holder.b.detail.text = o.detail
            holder.b.detail.visibility = if (o.detail.isNullOrEmpty()) View.GONE else View.VISIBLE
            holder.b.check.visibility = if (o.selected) View.VISIBLE else View.INVISIBLE
            holder.b.root.setOnClickListener {
                val i = holder.bindingAdapterPosition
                if (multi) {
                    options[i] = options[i].copy(selected = !options[i].selected)
                    notifyItemChanged(i)
                } else {
                    dismiss()
                }
                onPick(i)
            }
        }

        inner class Holder(val b: ItemOptionBinding) : RecyclerView.ViewHolder(b.root)
    }

    private companion object {
        const val PANEL_WIDTH_DP = 420
    }
}

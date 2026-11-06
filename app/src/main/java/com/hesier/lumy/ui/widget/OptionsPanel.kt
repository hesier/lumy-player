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

data class Option(val label: String, val detail: String? = null, val selected: Boolean = false, val icon: Int? = null)

/**
 * 从右侧滑出的选项面板。单选：选中后自动关闭；
 * 多选（[multi]）：确认键切换勾选，面板保持打开，返回键关闭。
 */
class OptionsPanel(
    context: Context,
    private val title: String,
    options: List<Option>,
    private val multi: Boolean = false,
    private val hint: String? = null,
    private val onPick: (Int) -> Unit,
) : Dialog(context, R.style.Theme_Lumy_Panel) {

    private val options = options.toMutableList()
    private var headerLabel: String? = null
    private var onHeaderClick: (() -> Unit)? = null
    private var focusHeader = false

    fun withHeaderAction(label: String, focused: Boolean = false, onClick: () -> Unit) = apply {
        headerLabel = label
        onHeaderClick = onClick
        focusHeader = focused
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ViewOptionsPanelBinding.inflate(LayoutInflater.from(context))
        setContentView(b.root)
        window?.apply {
            setLayout((PANEL_WIDTH_DP * context.resources.displayMetrics.density).toInt(), WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END)
        }
        b.title.text = title
        b.hint.text = hint
        b.hint.visibility = if (hint.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (onHeaderClick != null) {
            b.headerAction.root.visibility = View.VISIBLE
            b.headerAction.label.text = headerLabel
            b.headerAction.check.visibility = View.GONE
            b.headerAction.icon.setImageResource(R.drawable.ic_settings)
            b.headerAction.icon.visibility = View.VISIBLE
            b.headerAction.root.setOnClickListener {
                dismiss()
                onHeaderClick?.invoke()
            }
        }
        b.list.layoutManager = LinearLayoutManager(context)
        b.list.adapter = Adapter()
        val initial = options.indexOfFirst { it.selected }.coerceAtLeast(0)
        b.list.scrollToPosition(initial)
        b.list.post {
            if (focusHeader) b.headerAction.root.requestFocus()
            else b.list.findViewHolderForAdapterPosition(initial)?.itemView?.requestFocus()
        }
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
            holder.b.icon.setImageResource(o.icon ?: 0)
            holder.b.icon.visibility = if (o.icon != null) View.VISIBLE else View.GONE
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

package com.hesier.lumy.ui.widget

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import com.hesier.lumy.R
import com.hesier.lumy.databinding.ItemReorderBinding
import com.hesier.lumy.databinding.ViewOptionsPanelBinding

/**
 * 遥控器排序面板：确认键拿起一项（变金色），上下键移动，再按确认放下；返回键完成。
 * 触屏上点一项拿起，再点目标位置放下。
 * 关闭时把新顺序（id 列表）交给 [onDone]。
 */
class ReorderPanel(
    context: Context,
    private val title: String,
    items: List<Pair<String, String>>,
    private val onDone: (List<String>) -> Unit,
) : Dialog(context, R.style.Theme_Lumy_Panel) {

    private val items = items.toMutableList()
    private var holding = -1
    private lateinit var list: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ViewOptionsPanelBinding.inflate(LayoutInflater.from(context))
        setContentView(b.root)
        window?.apply {
            setLayout((420 * context.resources.displayMetrics.density).toInt(), WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END)
        }
        b.title.text = title
        list = b.list
        list.layoutManager = LinearLayoutManager(context)
        list.itemAnimator = null
        list.adapter = Adapter()
        list.post { list.getChildAt(0)?.requestFocus() }
        setOnDismissListener { onDone(this.items.map { it.first }) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (holding < 0 || event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val delta = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 1
            else -> return super.dispatchKeyEvent(event)
        }
        val target = holding + delta
        if (target in items.indices) {
            items.add(target, items.removeAt(holding))
            // 两行互换：直接重新绑定这两行的文字（焦点 View 不变，再把焦点移到新位置）
            val from = holding
            holding = target
            list.adapter?.notifyItemChanged(from)
            list.adapter?.notifyItemChanged(target)
            list.scrollToPosition(target)
            list.post { list.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
        }
        return true
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemReorderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val held = position == holding
            holder.b.label.text = items[position].second
            holder.b.label.setTextColor(ContextCompat.getColor(context, if (held) R.color.accent else R.color.text_primary))
            holder.b.hint.text = if (held) context.getString(R.string.reorder_moving) else ""
            holder.b.root.setOnClickListener {
                val p = holder.bindingAdapterPosition
                val old = holding
                if (old >= 0 && old != p) {
                    // 拿起一项后点另一行：直接放到那里。遥控器上焦点跟着拿起的项走，只有触屏会走到这里
                    items.add(p, items.removeAt(old))
                    holding = -1
                    notifyItemRangeChanged(minOf(old, p), abs(old - p) + 1)
                    return@setOnClickListener
                }
                holding = if (holding == p) -1 else p
                if (old >= 0) notifyItemChanged(old)
                notifyItemChanged(p)
            }
        }

        inner class Holder(val b: ItemReorderBinding) : RecyclerView.ViewHolder(b.root)
    }
}

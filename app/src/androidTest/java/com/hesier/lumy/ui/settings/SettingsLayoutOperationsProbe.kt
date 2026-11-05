package com.hesier.lumy.ui.settings

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/** Test-only wrapper. Layout includes create/bind/measure time; these nested timings must not be added. */
internal class SettingsLayoutOperationsProbe(private val list: RecyclerView) {
    private class Counter(var calls: Int = 0, var ns: Long = 0)
    private val counters = linkedMapOf<String, Counter>()
    private var inLayout = false
    @Suppress("UNCHECKED_CAST")
    private val delegate = checkNotNull(list.adapter) as RecyclerView.Adapter<RecyclerView.ViewHolder>
    private val proxy = Proxy()
    private val observer = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = proxy.notifyDataSetChanged()
        override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) = proxy.notifyItemRangeChanged(start, count, payload)
        override fun onItemRangeInserted(start: Int, count: Int) = proxy.notifyItemRangeInserted(start, count)
        override fun onItemRangeRemoved(start: Int, count: Int) = proxy.notifyItemRangeRemoved(start, count)
        override fun onItemRangeMoved(from: Int, to: Int, count: Int) {
            check(count == 1)
            proxy.notifyItemMoved(from, to)
        }
    }

    init {
        delegate.registerAdapterDataObserver(observer)
        list.layoutManager = Manager()
        list.swapAdapter(proxy, true)
    }

    fun reset() = counters.clear()

    fun summary(): String = counters.entries.joinToString(" ") { (name, count) ->
        "$name=${count.calls}/${String.format(Locale.ROOT, "%.3f", count.ns / 1_000_000.0)}ms"
    }

    fun close() = delegate.unregisterAdapterDataObserver(observer)

    private inline fun <T> timed(name: String, block: () -> T): T {
        val counter = counters.getOrPut(name) { Counter() }
        val start = System.nanoTime()
        try { return block() } finally {
            counter.calls++
            counter.ns += System.nanoTime() - start
        }
    }

    private inner class Manager : LinearLayoutManager(list.context) {
        override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
            inLayout = true
            try { timed("lmLayout") { super.onLayoutChildren(recycler, state) } }
            finally { inLayout = false }
        }

        override fun measureChildWithMargins(child: View, widthUsed: Int, heightUsed: Int) =
            timed("rowMeasure") { super.measureChildWithMargins(child, widthUsed, heightUsed) }

        override fun layoutDecoratedWithMargins(child: View, left: Int, top: Int, right: Int, bottom: Int) =
            timed("rowLayout") { super.layoutDecoratedWithMargins(child, left, top, right, bottom) }
    }

    private inner class Proxy : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        init { setHasStableIds(delegate.hasStableIds()) }
        override fun getItemCount() = delegate.itemCount
        override fun getItemId(position: Int) = delegate.getItemId(position)
        override fun getItemViewType(position: Int) = delegate.getItemViewType(position)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            timed(if (inLayout) "createInLayout" else "createOutsideLayout") { delegate.onCreateViewHolder(parent, viewType) }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) =
            onBindViewHolder(holder, position, emptyList())

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) =
            timed(if (inLayout) "bindInLayout" else "bindOutsideLayout") { delegate.onBindViewHolder(holder, position, payloads) }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) = delegate.onViewRecycled(holder)
        override fun onFailedToRecycleView(holder: RecyclerView.ViewHolder) = delegate.onFailedToRecycleView(holder)
        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) = delegate.onViewAttachedToWindow(holder)
        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) = delegate.onViewDetachedFromWindow(holder)
    }
}

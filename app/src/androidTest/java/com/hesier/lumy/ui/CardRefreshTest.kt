package com.hesier.lumy.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.hesier.lumy.R
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.core.Hub
import com.hesier.lumy.core.HubStyle
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.ui.widget.CardAdapter
import com.hesier.lumy.ui.widget.HubRowAdapter
import java.lang.reflect.Proxy

/** Runs adapter refresh regressions without a live media server. */
class CardRefreshTest {
    fun runAll() {
        checkCardUpdates()
        checkRowUpdates()
    }

    fun checkPlaybackPosition(context: Context) {
        val parent = FrameLayout(context)
        val adapter = CardAdapter(server(), HubStyle.LANDSCAPE, {}, {})
        val holder = adapter.createViewHolder(parent, HubStyle.LANDSCAPE.ordinal)
        val label = holder.itemView.findViewById<TextView>(R.id.playback_position)
        val item = MediaItem("progress", MediaType.MOVIE, "Movie", viewOffsetMs = 1_494_000)
        holder.bind(item)
        check(View.VISIBLE == label.visibility && "24:54" == label.text.toString())
        holder.bind(item.copy(type = MediaType.EPISODE, viewOffsetMs = 5_094_000))
        check(View.VISIBLE == label.visibility && "1:24:54" == label.text.toString())
        for (position in listOf(null, 0L, -1L)) {
            holder.bind(item.copy(viewOffsetMs = position))
            check(View.GONE == label.visibility && label.text.isEmpty())
        }
        holder.bind(item)
        holder.bind(null)
        check(View.GONE == label.visibility && label.text.isEmpty())
        holder.bind(item.copy(type = MediaType.SHOW))
        check(View.GONE == label.visibility)
        for (style in listOf(HubStyle.POSTER, HubStyle.PERSON)) {
            val other = CardAdapter(server(), style, {}, {}).createViewHolder(parent, style.ordinal)
            other.bind(item)
            check(null == other.itemView.findViewById<TextView>(R.id.playback_position))
        }
    }

    private fun server(): MediaServer = Proxy.newProxyInstance(
        MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java),
    ) { _, method, _ -> error("Unexpected server call: ${method.name}") } as MediaServer

    private fun checkCardUpdates() {
        val adapter = CardAdapter(server(), HubStyle.POSTER, {}, {})
        val first = MediaItem("first", MediaType.MOVIE, "First")
        val second = MediaItem("second", MediaType.MOVIE, "Second")
        adapter.submit(listOf(first, second))
        val events = Events().also(adapter::registerAdapterDataObserver)
        adapter.submit(listOf(first.copy(), second.copy()))
        check(events.updates.isEmpty()) { "Unchanged cards must retain their views" }
        val watched = second.copy(watched = true, viewOffsetMs = 1000)
        adapter.submit(listOf(first, watched))
        check(listOf("change:1:1") == events.updates) { events.updates }
        check(2 == adapter.itemCount)
        events.updates.clear()
        adapter.submit(listOf(watched, first))
        check(events.updates.any { it.startsWith("move:") }) { events.updates }
        check(0 == adapter.indexOf(second.id))
        events.updates.clear()
        adapter.submit(listOf(watched))
        check(listOf("remove:1:1") == events.updates) { events.updates }
        events.updates.clear()
        adapter.submit(emptyList())
        check(listOf("remove:0:1") == events.updates) { events.updates }
        events.updates.clear()
        adapter.submit(listOf(first))
        check(listOf("insert:0:1") == events.updates) { events.updates }
    }

    private fun checkRowUpdates() {
        val item = MediaItem("first", MediaType.MOVIE, "First")
        val row = Hub("recent", "Recent", HubStyle.POSTER, listOf(item))
        val other = row.copy(id = "other")
        val adapter = HubRowAdapter(server(), listOf(row, other), {}, {}, {})
        val events = Events().also(adapter::registerAdapterDataObserver)
        adapter.update(listOf(row.copy(), other.copy()))
        check(events.updates.isEmpty()) { "Unchanged rows must not rebind" }
        adapter.update(listOf(row.copy(items = listOf(item.copy(watched = true))), other))
        check(listOf("change:0:1") == events.updates) { events.updates }
        events.updates.clear()
        adapter.update(listOf(other))
        check(listOf("reset") == events.updates) { events.updates }
        check(1 == adapter.itemCount)
    }

    private class Events : RecyclerView.AdapterDataObserver() {
        val updates = mutableListOf<String>()
        override fun onChanged() { updates += "reset" }
        override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) {
            updates += "change:$start:$count"
        }
        override fun onItemRangeInserted(start: Int, count: Int) { updates += "insert:$start:$count" }
        override fun onItemRangeRemoved(start: Int, count: Int) { updates += "remove:$start:$count" }
        override fun onItemRangeMoved(from: Int, to: Int, count: Int) { updates += "move:$from:$to:$count" }
    }
}

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
        checkEpisodeTime(holder, item.copy(type = MediaType.EPISODE, durationMs = 2_700_000))
        checkWatchedBadges(context)
        holder.bind(item.copy(type = MediaType.SHOW))
        check(View.GONE == label.visibility)
        for (style in listOf(HubStyle.POSTER, HubStyle.PERSON)) {
            val other = CardAdapter(server(), style, {}, {}).createViewHolder(parent, style.ordinal)
            other.bind(item)
            check(null == other.itemView.findViewById<TextView>(R.id.playback_position))
        }
    }

    private fun checkEpisodeTime(holder: CardAdapter.Holder, episode: MediaItem) {
        val label = holder.itemView.findViewById<TextView>(R.id.playback_position)
        holder.bind(episode)
        check("24:54 / 45:00" == label.text.toString())
        for (offset in listOf(null, 0L, -1L, 2_700_000L, 3_000_000L)) {
            holder.bind(episode.copy(viewOffsetMs = offset))
            check(View.VISIBLE == label.visibility && "45:00" == label.text.toString())
        }
        holder.bind(episode.copy(watched = true))
        check("45:00" == label.text.toString())
        holder.bind(episode.copy(durationMs = 5_400_000, viewOffsetMs = 3_600_000))
        check("1:00:00 / 1:30:00" == label.text.toString())
        holder.bind(null)
        check(View.GONE == label.visibility && label.text.isEmpty())
    }

    private fun checkWatchedBadges(context: Context) {
        for (style in listOf(HubStyle.POSTER, HubStyle.LANDSCAPE)) {
            val adapter = CardAdapter(server(), style, {}, {})
            val holder = adapter.createViewHolder(FrameLayout(context), style.ordinal)
            val watched = holder.itemView.findViewById<View>(R.id.watched_badge)
            val corner = holder.itemView.findViewById<View>(R.id.unwatched_corner)
            val progress = holder.itemView.findViewById<View>(R.id.progress)
            for (type in listOf(MediaType.MOVIE, MediaType.EPISODE, MediaType.SHOW, MediaType.SEASON)) {
                val item = MediaItem("watched", type, "Watched", watched = true, durationMs = 2_700_000, viewOffsetMs = 1_000)
                holder.bind(item)
                check(View.VISIBLE == watched.visibility && View.GONE == corner.visibility)
                check(View.GONE == progress.visibility)
                holder.bind(item.copy(watched = false, viewOffsetMs = null))
                check(View.GONE == watched.visibility)
                check((if (type == MediaType.MOVIE) View.VISIBLE else View.GONE) == corner.visibility)
                holder.bind(item.copy(type = MediaType.SHOW, unwatchedCount = 2))
                check(View.GONE == watched.visibility)
                val badge = holder.itemView.findViewById<TextView>(R.id.badge)
                check(View.VISIBLE == badge.visibility && "2" == badge.text.toString())
                holder.bind(null)
                check(View.GONE == watched.visibility && View.GONE == corner.visibility)
            }
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

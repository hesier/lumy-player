package com.hesier.lumy.ui.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.core.Hub
import com.hesier.lumy.core.HubLabel
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.R
import com.hesier.lumy.databinding.ItemHubRowBinding

/** 一行一个 Hub（标题 + 横向卡片），首页和搜索结果共用 */
class HubRowAdapter(
    private val server: MediaServer,
    private var hubs: List<Hub>,
    private val onFocused: (MediaItem) -> Unit,
    private val onClick: (MediaItem) -> Unit,
    private val onLongClick: (MediaItem) -> Unit,
) : RecyclerView.Adapter<HubRowAdapter.Holder>() {

    // 各行共用卡片 View 池，减少滚动时的 inflate
    private val pool = RecyclerView.RecycledViewPool()

    /** 每一行上次停留的位置：上下切换回来时回到原处，不会按几何位置随便选一张 */
    private val lastPositions = HashMap<String, Int>()

    override fun getItemCount() = hubs.size

    /** 行结构不变时只换数据，焦点留在原位置 */
    fun update(newHubs: List<Hub>) {
        val sameRows = newHubs.map { it.id to it.style } == hubs.map { it.id to it.style }
        hubs = newHubs
        if (sameRows) notifyItemRangeChanged(0, hubs.size) else notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val b = ItemHubRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        b.items.layoutManager = StartAlignedLayoutManager(parent.context, RecyclerView.HORIZONTAL).apply {
            initialPrefetchItemCount = 6
        }
        b.items.setRecycledViewPool(pool)
        b.items.setHasFixedSize(true)
        return Holder(b)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(hubs[position])

    inner class Holder(private val b: ItemHubRowBinding) : RecyclerView.ViewHolder(b.root) {
        private var boundId: String? = null
        private var cards: CardAdapter? = null

        init {
            b.items.preferredPosition = {
                val layout = b.items.layoutManager as LinearLayoutManager
                boundId?.let { lastPositions[it] } ?: layout.findFirstCompletelyVisibleItemPosition()
            }
        }

        fun bind(hub: Hub) {
            b.title.text = hub.displayTitle(b.root.context)
            val existing = cards
            if (existing != null && boundId == hub.id) {
                existing.submit(hub.items)
                return
            }
            boundId = hub.id
            val rowId = hub.id
            cards = CardAdapter(server, hub.style, onFocused = { item ->
                cards?.indexOf(item.id)?.takeIf { it >= 0 }?.let { lastPositions[rowId] = it }
                onFocused(item)
            }, onClick, onLongClick).also { it.submit(hub.items) }
            b.items.adapter = cards
            // 行被回收后重新显示时，滚回上次的位置
            (b.items.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(lastPositions[rowId] ?: 0, 0)
        }
    }
}

/** 客户端自己拼的行按当前语言显示；服务器给的行（Plex 首页）用服务器的标题 */
fun Hub.displayTitle(context: Context): String = when (val l = label) {
    null -> title
    HubLabel.ContinueWatching -> context.getString(R.string.hub_continue_watching)
    HubLabel.NextUp -> context.getString(R.string.up_next)
    is HubLabel.RecentlyAdded -> context.getString(R.string.hub_recently_added, l.library)
    is HubLabel.SearchResults -> when (l.type) {
        MediaType.MOVIE -> l.library ?: context.getString(R.string.search_movies)
        MediaType.SHOW -> l.library ?: context.getString(R.string.search_shows)
        MediaType.SEASON -> l.library?.let { context.getString(R.string.search_library_seasons, it) } ?: context.getString(R.string.search_seasons)
        MediaType.EPISODE -> l.library?.let { context.getString(R.string.search_library_episodes, it) } ?: context.getString(R.string.search_episodes)
        else -> l.library ?: title
    }
}

package com.hesier.lumy.ui.widget

import android.content.Context
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.core.Hub
import com.hesier.lumy.core.HubLabel
import com.hesier.lumy.core.HubStyle
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

    // 各行共用卡片 View 池，减少滚动时的 inflate。默认每种只留 5 张，一行就有十来张，不够用
    private val pool = RecyclerView.RecycledViewPool().apply {
        HubStyle.values().forEach { setMaxRecycledViews(it.ordinal, POOL_SIZE) }
    }

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

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) = prewarm(recyclerView)

    /**
     * 主线程空闲时一张一张预先创建卡片放进池里。新的一行滚进来要一次创建十几张卡片，
     * 弱 CPU 上每张十毫秒左右，全挤在同一帧里会卡顿几百毫秒。
     */
    private fun prewarm(parent: RecyclerView) {
        val queue = ArrayDeque(hubs.map { it.style }.distinct().flatMap { style -> List(PREWARM_PER_STYLE) { style } })
        val factories = HashMap<HubStyle, CardAdapter>()
        // 空闲回调只在消息队列处理完一条消息后才会再触发，画面静止时不会再来：每次投一条空消息唤醒下一轮
        val wake = Runnable {}
        Looper.myQueue().addIdleHandler {
            if (!parent.isAttachedToWindow || parent.adapter !== this) return@addIdleHandler false
            // 滚动动画进行中不插队，等停下来再做
            if (parent.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                parent.postDelayed(wake, PREWARM_RETRY_MS)
                return@addIdleHandler true
            }
            val style = queue.removeFirstOrNull() ?: return@addIdleHandler false
            if (pool.getRecycledViewCount(style.ordinal) < PREWARM_PER_STYLE) {
                val factory = factories.getOrPut(style) { CardAdapter(server, style, onFocused, onClick, onLongClick) }
                pool.putRecycledView(factory.createViewHolder(parent, style.ordinal))
            }
            if (queue.isNotEmpty()) parent.post(wake)
            queue.isNotEmpty()
        }
    }

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

    private companion object {
        const val POOL_SIZE = 40
        /** 大约两行的卡片 */
        const val PREWARM_PER_STYLE = 20
        const val PREWARM_RETRY_MS = 200L
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

package com.hesier.lumy.ui.widget

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.hesier.lumy.core.HubStyle
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.R
import com.hesier.lumy.ui.cardSubtitle
import com.hesier.lumy.ui.cardTitle
import com.hesier.lumy.ui.landscapeSubtitle
import coil.dispose
import coil.load

/**
 * 海报卡片 / 横版卡片，首页各行、媒体库网格、搜索结果共用。
 * 支持稀疏数据：先用 [resize] 占好总数，未加载的位置显示占位卡片，
 * 绑定到占位时通过 [onMissing] 通知外部去加载那一页，便于快速跳转到任意位置。
 */
class CardAdapter(
    private val server: MediaServer,
    private val style: HubStyle,
    private val onFocused: (MediaItem) -> Unit,
    private val onClick: (MediaItem) -> Unit,
    private val onLongClick: ((MediaItem) -> Unit)? = null,
    private val onMissing: ((position: Int) -> Unit)? = null,
) : RecyclerView.Adapter<CardAdapter.Holder>() {

    private val items = ArrayList<MediaItem?>()

    /** 选中（不一定有焦点）的条目，显示金色描边；剧集列表里表示“当前详情对应的那一集” */
    var selectedId: String? = null
        set(value) {
            val old = field
            field = value
            if (old == value) return
            // 直接改屏幕上卡片的选中状态，不经过 notifyItemChanged：
            // 通知会让 RecyclerView 的条目动画器介入，它会 cancel 卡片上正在进行的放大动画（闪一下 / 没有动画）
            val rv = recyclerView ?: return
            for (i in 0 until rv.childCount) {
                val child = rv.getChildAt(i)
                val id = items.getOrNull(rv.getChildAdapterPosition(child))?.id
                child.isSelected = id != null && id == value
            }
        }

    fun indexOf(id: String): Int = items.indexOfFirst { it?.id == id }

    fun submit(list: List<MediaItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun resize(total: Int) {
        items.clear()
        repeat(total) { items.add(null) }
        notifyDataSetChanged()
    }

    fun fill(offset: Int, list: List<MediaItem>) {
        list.forEachIndexed { i, item -> if (offset + i < items.size) items[offset + i] = item }
        notifyItemRangeChanged(offset, list.size.coerceAtMost(items.size - offset))
    }

    fun isLoaded(position: Int) = items.getOrNull(position) != null

    /** 标记已看等操作后，按 id 原地替换 */
    fun update(item: MediaItem) {
        items.forEachIndexed { i, old -> if (old?.id == item.id) { items[i] = item; notifyItemChanged(i) } }
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = style.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = when (style) {
            HubStyle.POSTER -> R.layout.item_card_poster
            HubStyle.LANDSCAPE -> R.layout.item_card_landscape
            HubStyle.PERSON -> R.layout.item_card_person
        }
        return Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.bind(item)
        if (item == null) onMissing?.invoke(position)
    }

    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
        // 数据刷新（进度、已看）时不做替换动画，焦点卡片保持同一个 View
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (this.recyclerView === recyclerView) this.recyclerView = null
    }

    override fun onViewRecycled(holder: Holder) {
        holder.image.dispose()
        holder.image.setImageDrawable(null)
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.image)
        private val frame: View = view.findViewById(R.id.frame)
        private val title: TextView = view.findViewById(R.id.title)
        private val subtitle: TextView = view.findViewById(R.id.subtitle)
        private val corner: View = view.findViewById(R.id.unwatched_corner)
        private val badge: TextView = view.findViewById(R.id.badge)
        private val progress: ProgressLine = view.findViewById(R.id.progress)
        private var item: MediaItem? = null

        /**
         * 首页各行共用卡片池，卡片可能是别的行（别的 adapter）创建的：
         * 回调和选中状态要取当前绑定的 adapter，不能用创建它的那个
         */
        private val owner: CardAdapter get() = bindingAdapter as? CardAdapter ?: this@CardAdapter

        init {
            frame.clipToRoundedBackground()
            // 头像用圆形：背景是椭圆，按背景裁剪就是圆
            view.scaleOnFocus(frame)
            val baseFocus = view.onFocusChangeListener
            view.setOnFocusChangeListener { v, hasFocus ->
                baseFocus?.onFocusChange(v, hasFocus)
                if (hasFocus) item?.let(owner.onFocused)
            }
            view.setOnClickListener { item?.let(owner.onClick) }
            view.setOnLongClickListener {
                val longClick = owner.onLongClick ?: return@setOnLongClickListener false
                item?.let(longClick)
                item != null
            }
            // 很多遥控器长按确认键不可靠，菜单键同样打开操作菜单
            view.setOnKeyListener { _, keyCode, event ->
                val longClick = owner.onLongClick ?: return@setOnKeyListener false
                if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                    item?.let(longClick)
                }
                keyCode == KeyEvent.KEYCODE_MENU && item != null
            }
        }

        fun bind(item: MediaItem?) {
            this.item = item
            itemView.syncFocusScale(frame)
            itemView.isSelected = item != null && item.id == owner.selectedId
            if (item == null) {
                title.text = null
                subtitle.text = null
                image.dispose()
                image.setImageDrawable(null)
                corner.visibility = View.GONE
                badge.visibility = View.GONE
                progress.visibility = View.GONE
                return
            }
            val ctx = itemView.context
            title.text = item.cardTitle()
            val sub = if (style == HubStyle.LANDSCAPE) item.landscapeSubtitle(ctx) else item.cardSubtitle(ctx)
            subtitle.text = sub
            subtitle.visibility = if (sub.isNullOrEmpty()) View.GONE else View.VISIBLE

            val ref = if (style == HubStyle.LANDSCAPE) item.thumb ?: item.backdrop ?: item.poster else item.poster
            val lp = frame.layoutParams
            if (ref != null) {
                image.load(server.imageUrl(ref, lp.width, lp.height)) { size(lp.width, lp.height) }
            } else {
                image.dispose()
                image.setImageDrawable(null)
            }

            val unwatchedEpisodes = item.unwatchedCount?.takeIf { it > 0 && item.type != MediaType.EPISODE }
            badge.visibility = if (unwatchedEpisodes != null) View.VISIBLE else View.GONE
            badge.text = unwatchedEpisodes?.toString()
            val inProgress = item.progress?.takeIf { it > 0f }
            corner.visibility =
                if (unwatchedEpisodes == null && !item.watched && inProgress == null && item.type == MediaType.MOVIE) View.VISIBLE else View.GONE
            progress.visibility = if (inProgress != null) View.VISIBLE else View.GONE
            progress.progress = inProgress ?: 0f
        }
    }
}

package com.hesier.lumy.ui.player

import android.view.LayoutInflater
import android.view.View
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.SimpleItemAnimator
import coil.dispose
import coil.load
import com.hesier.lumy.R
import com.hesier.lumy.core.Chapter
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.ui.formatClock
import com.hesier.lumy.ui.widget.clipToRoundedBackground
import com.hesier.lumy.ui.widget.scaleOnFocus
import com.hesier.lumy.ui.widget.syncFocusScale

/** 电影在播放器按钮行按下键展开的章节行，和剧集行用同样的横版卡片 */
class ChapterAdapter(
    private val server: MediaServer,
    private val chapters: List<Chapter>,
    private val onClick: (Chapter) -> Unit,
) : RecyclerView.Adapter<ChapterAdapter.Holder>() {

    /** 当前播放位置所在的章节，显示选中描边 */
    var selected = -1
        set(value) {
            val old = field
            field = value
            if (old == value) return
            // 和 CardAdapter 一样直接改屏幕上的卡片，避免条目动画打断焦点放大
            val rv = recyclerView ?: return
            for (i in 0 until rv.childCount) {
                val child = rv.getChildAt(i)
                child.isSelected = rv.getChildAdapterPosition(child) == value
            }
        }

    override fun getItemCount() = chapters.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_card_landscape, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(position)

    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
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
        /** 没有章节缩略图时（如 Jellyfin 没开章节图片提取）在占位卡片中间显示开始时间 */
        private val placeholderTime = TextView(view.context).apply {
            TextViewCompat.setTextAppearance(this, R.style.Text_Title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            visibility = View.GONE
        }

        init {
            frame.clipToRoundedBackground()
            view.scaleOnFocus(frame)
            (frame as ViewGroup).addView(
                placeholderTime,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
            view.findViewById<View>(R.id.unwatched_corner).visibility = View.GONE
            view.findViewById<View>(R.id.badge).visibility = View.GONE
            view.findViewById<View>(R.id.progress).visibility = View.GONE
            view.setOnClickListener {
                chapters.getOrNull(bindingAdapterPosition)?.let(onClick)
            }
        }

        fun bind(position: Int) {
            val chapter = chapters[position]
            itemView.syncFocusScale(frame)
            itemView.isSelected = position == selected
            val ctx = itemView.context
            title.text = "${position + 1}. ${chapter.title ?: ctx.getString(R.string.chapter_n, position + 1)}"
            subtitle.text = formatClock(chapter.startMs)
            placeholderTime.text = subtitle.text
            placeholderTime.visibility = if (chapter.thumb == null) View.VISIBLE else View.GONE
            val lp = frame.layoutParams
            val ref = chapter.thumb
            if (ref != null) {
                image.load(server.imageUrl(ref, lp.width, lp.height)) { size(lp.width, lp.height) }
            } else {
                image.dispose()
                image.setImageDrawable(null)
            }
        }
    }
}

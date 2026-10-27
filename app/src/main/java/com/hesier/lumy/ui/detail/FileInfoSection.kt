package com.hesier.lumy.ui.detail

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.R
import com.hesier.lumy.core.AudioStream
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.SubtitleStream
import com.hesier.lumy.core.VideoStream
import com.hesier.lumy.ui.formatClock
import com.hesier.lumy.ui.widget.StartAlignedLayoutManager
import com.hesier.lumy.ui.widget.scaleOnFocus
import com.hesier.lumy.ui.widget.syncFocusScale
import java.util.Locale

/**
 * 详情页底部的文件信息：文件、视频、每条音轨各一张卡片，字幕每 [SUBTITLES_PER_CARD] 条一张，
 * 几十条字幕时左右翻看。跟随当前选择的版本。
 */
class FileInfoSection(private val title: View, private val row: RecyclerView, private val enabled: Boolean) {

    private val context: Context get() = row.context
    private val adapter = CardsAdapter()

    init {
        // 页面左边没有侧边栏，行首按左停在原地，不跳到上面的行
        row.layoutManager = StartAlignedLayoutManager(context, RecyclerView.HORIZONTAL, stayAtStart = true)
        row.adapter = adapter
    }

    fun render(version: MediaVersion?) {
        val cards = version?.takeIf { enabled }?.let(::cards).orEmpty()
        title.visibility = if (cards.isEmpty()) View.GONE else View.VISIBLE
        row.visibility = title.visibility
        adapter.submit(cards)
    }

    // ---- 卡片内容 ----

    private sealed interface Line {
        /** 卡片里的主标题（文件名、轨道标题） */
        class Title(val text: String, val maxLines: Int = 1) : Line
        class Field(val label: Int, val value: String) : Line
        /** 字幕：标题一行，语言 / 编码等一行 */
        class Entry(val title: String, val detail: String) : Line
    }

    private class Card(val header: String, val lines: List<Line>)

    private fun cards(v: MediaVersion): List<Card> = buildList {
        add(fileCard(v))
        v.video?.let { add(videoCard(it)) }
        v.audio.forEachIndexed { i, a -> add(audioCard(a, i, v.audio.size)) }
        val subs = v.subtitles.chunked(SUBTITLES_PER_CARD)
        subs.forEachIndexed { i, chunk ->
            val from = i * SUBTITLES_PER_CARD + 1
            val count = if (subs.size > 1) " · $from–${from + chunk.size - 1} / ${v.subtitles.size}" else ""
            add(Card(context.getString(R.string.file_info_subtitles) + count, chunk.map(::subtitleEntry)))
        }
    }

    private fun fileCard(v: MediaVersion): Card {
        val size = v.parts.mapNotNull { it.sizeBytes }.takeIf { it.isNotEmpty() }?.sum()
        val duration = v.parts.mapNotNull { it.durationMs }.takeIf { it.isNotEmpty() }?.sum()
        val name = v.part.file?.substringAfterLast('/')?.substringAfterLast('\\')
        return Card(
            context.getString(R.string.file_info_file),
            listOfNotNull(
                name?.let { Line.Title(it, maxLines = 2) },
                size?.let { Line.Field(R.string.file_info_size, Formatter.formatFileSize(context, it)) },
                duration?.let { Line.Field(R.string.file_info_duration, formatClock(it)) },
                listOfNotNull(v.container?.uppercase(), v.parts.size.takeIf { it > 1 }?.let { context.getString(R.string.file_info_parts, it) })
                    .joinToString(" · ").ifEmpty { null }?.let { Line.Field(R.string.file_info_container, it) },
                v.bitrateKbps?.let { Line.Field(R.string.file_info_bitrate, mbps(it)) },
            ),
        )
    }

    private fun videoCard(s: VideoStream): Card = Card(
        context.getString(R.string.info_video),
        listOfNotNull(
            (s.title ?: s.resolutionLabel)?.let { Line.Title(it) },
            Line.Field(R.string.file_info_codec, listOfNotNull(s.codec.uppercase(), s.profile).joinToString(" ")),
            s.width?.let { w -> s.height?.let { h -> Line.Field(R.string.file_info_resolution, "$w×$h") } },
            s.frameRate?.let { Line.Field(R.string.file_info_frame_rate, "%.3f".format(it).trimEnd('0').trimEnd('.', ',') + " fps") },
            s.bitDepth?.let { Line.Field(R.string.file_info_bit_depth, "$it-bit") },
            s.hdr.label?.let { label -> Line.Field(R.string.file_info_dynamic_range, s.doviProfile?.let { "$label P$it" } ?: label) },
            s.bitrateKbps?.let { Line.Field(R.string.file_info_bitrate, mbps(it)) },
            languageName(s.language)?.let { Line.Field(R.string.file_info_language, it) },
        ),
    )

    private fun audioCard(a: AudioStream, index: Int, total: Int): Card {
        val header = listOfNotNull(
            context.getString(R.string.info_audio),
            "${index + 1} / $total".takeIf { total > 1 },
            context.getString(R.string.file_info_default).takeIf { a.selected },
        ).joinToString(" · ")
        return Card(
            header,
            listOfNotNull(
                Line.Title(a.title),
                languageName(a.language)?.let { Line.Field(R.string.file_info_language, it) },
                a.codec.takeIf { it.isNotBlank() }?.let { Line.Field(R.string.file_info_codec, it.uppercase()) },
                a.channelsLabel?.let { Line.Field(R.string.file_info_channels, it) },
                a.bitrateKbps?.let { Line.Field(R.string.file_info_bitrate, "$it kbps") },
                a.sampleRate?.let { Line.Field(R.string.file_info_sample_rate, "%.1f".format(it / 1000f).removeSuffix(".0").removeSuffix(",0") + " kHz") },
            ),
        )
    }

    private fun subtitleEntry(s: SubtitleStream) = Line.Entry(
        s.title,
        listOfNotNull(
            languageName(s.language),
            s.codec.takeIf { it.isNotBlank() }?.uppercase(),
            context.getString(R.string.subtitle_forced).takeIf { s.forced },
            context.getString(R.string.file_info_external).takeIf { s.external },
            context.getString(R.string.file_info_default).takeIf { s.selected },
        ).joinToString(" · "),
    )

    private fun mbps(kbps: Int) = "%.1f Mbps".format(kbps / 1000f)

    /** 服务端给的是 zh-Hans、en 这样的标签或 eng、chi 这样的三字母代码，按界面语言显示名称 */
    private fun languageName(code: String?): String? {
        val c = code?.trim()?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) } ?: return null
        val tag = c.replace('_', '-')
        val primary = tag.substringBefore('-').lowercase()
        val locale = Locale.forLanguageTag(if (primary.length == 3) (iso3To2[primary] ?: primary) + tag.drop(primary.length) else tag)
        val name = locale.getDisplayName(ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.getDefault())
        return name.takeIf { it.isNotBlank() && !it.equals(locale.toLanguageTag(), ignoreCase = true) } ?: c
    }

    // ---- 列表 ----

    private inner class CardsAdapter : RecyclerView.Adapter<CardsAdapter.Holder>() {
        private var cards: List<Card> = emptyList()

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<Card>) {
            cards = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = cards.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file_info_card, parent, false)
            return Holder(view).also { view.scaleOnFocus(it.frame) }
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val card = cards[position]
            holder.itemView.syncFocusScale(holder.frame)
            holder.header.text = card.header
            holder.lines.removeAllViews()
            card.lines.forEachIndexed { i, line -> holder.lines.addView(lineView(line, first = i == 0)) }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val frame: View = view.findViewById(R.id.frame)
            val header: TextView = view.findViewById(R.id.header)
            val lines: LinearLayout = view.findViewById(R.id.lines)
        }
    }

    private fun lineView(line: Line, first: Boolean): View = when (line) {
        is Line.Title -> text(line.text, 14f, R.color.text_primary, line.maxLines, bold = true).withTopMargin(if (first) 0 else 4)
        is Line.Field -> {
            val label = context.getString(line.label)
            val span = SpannableStringBuilder(label).append("  ").append(line.value)
            span.setSpan(ForegroundColorSpan(ContextCompat.getColor(context, R.color.text_tertiary)), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text(span, 13f, R.color.text_secondary, 1).withTopMargin(if (first) 0 else 3)
        }
        is Line.Entry -> LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(line.title, 14f, R.color.text_primary, 1))
            if (line.detail.isNotEmpty()) addView(text(line.detail, 12f, R.color.text_tertiary, 1))
        }.withTopMargin(if (first) 0 else 6)
    }

    private fun text(value: CharSequence, sizeSp: Float, color: Int, maxLines: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(ContextCompat.getColor(context, color))
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun View.withTopMargin(dp: Int): View = apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (dp * resources.displayMetrics.density).toInt()
        }
    }

    private companion object {
        const val SUBTITLES_PER_CARD = 4

        /** ISO 639-2 三字母代码 → 两字母，Locale 对三字母代码的名称支持不全 */
        val iso3To2: Map<String, String> by lazy {
            val map = HashMap<String, String>()
            for (two in Locale.getISOLanguages()) {
                runCatching { Locale(two).isO3Language }.getOrNull()?.let { map[it] = two }
            }
            // 书目代码（B 码）和术语代码不同的几种
            map += mapOf(
                "chi" to "zh", "ger" to "de", "fre" to "fr", "dut" to "nl", "cze" to "cs", "gre" to "el",
                "per" to "fa", "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu",
                "bur" to "my", "geo" to "ka", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms",
                "wel" to "cy", "tib" to "bo",
            )
            map
        }
    }
}

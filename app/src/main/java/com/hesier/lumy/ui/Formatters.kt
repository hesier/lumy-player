package com.hesier.lumy.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import androidx.core.content.ContextCompat
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.R
import com.hesier.lumy.data.SavedServer

/** 卡片下方的主标题：剧集显示剧名，和官方客户端一致 */
fun MediaItem.cardTitle(): String = if (type == MediaType.EPISODE) showTitle ?: title else title

fun MediaItem.cardSubtitle(context: Context): String? = when (type) {
    MediaType.EPISODE -> episodeCode(context)
    MediaType.SHOW -> unwatchedCount?.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.unwatched_episodes, it, it) }
        ?: year?.toString()
    MediaType.SEASON -> showTitle
    MediaType.COLLECTION, MediaType.PLAYLIST -> childCount?.let { context.resources.getQuantityString(R.plurals.library_count, it, it) }
    MediaType.PERSON -> summary
    else -> year?.toString()
}

/** 横版卡片（继续观看）：剧集显示“第 x 季 · 第 y 集”，电影显示剩余时长 */
fun MediaItem.landscapeSubtitle(context: Context): String? {
    if (type == MediaType.EPISODE) return episodeCode(context)
    val remaining = durationMs?.let { d -> viewOffsetMs?.let { d - it } }
    return remaining?.takeIf { it > 60_000 }?.let { context.getString(R.string.remaining, formatDuration(context, it)) }
        ?: year?.toString()
}

fun MediaItem.heroTitle(): String = cardTitle()

/** 首页顶部：名称 + 小字副标题（剧集：第几集 · 集名；季：季名） */
fun MediaItem.heroLine(context: Context): CharSequence {
    val sub = when (type) {
        MediaType.EPISODE -> listOfNotNull(episodeCode(context), title.takeIf { it.isNotBlank() }).joinToString(" · ")
        MediaType.SEASON -> title
        else -> null
    }?.takeIf { it.isNotBlank() } ?: return heroTitle()
    val name = if (type == MediaType.SEASON) showTitle ?: title else heroTitle()
    return SpannableStringBuilder(name).apply {
        append("   ")
        val start = length
        append(sub)
        setSpan(RelativeSizeSpan(0.5f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(ContextCompat.getColor(context, R.color.text_secondary)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

fun MediaItem.heroMeta(context: Context): String = buildList {
    when (type) {
        MediaType.EPISODE -> {
            episodeCode(context)?.let(::add)
            add(title)
        }
        MediaType.SHOW -> {
            year?.let { add(it.toString()) }
            unwatchedCount?.takeIf { it > 0 }?.let { add(context.resources.getQuantityString(R.plurals.unwatched_episodes, it, it)) }
        }
        else -> {
            year?.let { add(it.toString()) }
            durationMs?.let { add(formatDuration(context, it)) }
        }
    }
}.joinToString("  ·  ")

private fun MediaItem.episodeCode(context: Context): String? {
    val s = seasonNumber ?: return null
    val e = episodeNumber ?: return null
    return context.getString(R.string.episode_code, s, e)
}

fun formatDuration(context: Context, ms: Long): String {
    val totalMinutes = (ms / 60_000).toInt().coerceAtLeast(1)
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) context.getString(R.string.hours_minutes, h, m) else context.getString(R.string.minutes, m)
}

/** 播放器和按钮上的时间：1:02:03 或 12:34 */
fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun ServerKind.label(context: Context): String = context.getString(
    when (this) {
        ServerKind.PLEX -> R.string.kind_plex
        ServerKind.JELLYFIN -> R.string.kind_jellyfin
        ServerKind.EMBY -> R.string.kind_emby
    },
)

/** 带品牌色的类型图标，不要再着色 */
val ServerKind.icon: Int
    get() = when (this) {
        ServerKind.PLEX -> R.drawable.ic_kind_plex
        ServerKind.JELLYFIN -> R.drawable.ic_kind_jellyfin
        ServerKind.EMBY -> R.drawable.ic_kind_emby
    }

/** Server subtitles show the user and address; the icon identifies the server kind. */
fun SavedServer.detail(context: Context): String =
    userName?.let { context.getString(R.string.server_detail_user, it, session.baseUrl) }
        ?: context.getString(R.string.server_detail, session.baseUrl)

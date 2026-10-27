package com.hesier.lumy.core

/**
 * 与具体服务端无关的数据模型。UI 和播放器只依赖这里的类型，
 * Plex / Emby / Jellyfin 各自负责把自己的 API 响应映射过来。
 */

enum class MediaType { MOVIE, SHOW, SEASON, EPISODE, MUSIC, PHOTO, COLLECTION, PLAYLIST, PERSON, OTHER }

data class Library(
    val id: String,
    val title: String,
    val type: MediaType,
)

/**
 * 图片引用是服务端内部的不透明路径，需要通过 [MediaServer.imageUrl] 换成可加载的地址，
 * 这样可以按显示尺寸向服务端请求缩略图，而不是下载原图。
 */
data class MediaItem(
    val id: String,
    val type: MediaType,
    val title: String,
    /** 季的 id（剧集）或剧的 id（季） */
    val parentId: String? = null,
    /** 剧的 id（剧集） */
    val grandparentId: String? = null,
    /** 剧集所属的剧名，其他类型为空 */
    val showTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val year: Int? = null,
    val summary: String? = null,
    val poster: String? = null,
    /** 横版缩略图（剧集截图），用于“继续观看”这类横向卡片 */
    val thumb: String? = null,
    val backdrop: String? = null,
    val durationMs: Long? = null,
    val viewOffsetMs: Long? = null,
    val watched: Boolean = false,
    /** 剧集/季中未看的集数 */
    val unwatchedCount: Int? = null,
    /** 合集 / 播放列表里的条目数 */
    val childCount: Int? = null,
) {
    val progress: Float?
        get() {
            val offset = viewOffsetMs ?: return null
            val duration = durationMs?.takeIf { it > 0 } ?: return null
            return (offset.toFloat() / duration).coerceIn(0f, 1f)
        }
}

enum class HubStyle { POSTER, LANDSCAPE, PERSON }

data class Hub(
    val id: String,
    /** 服务器给的行标题；客户端自己拼的行这里是英文兜底，界面按 [label] 翻译 */
    val title: String,
    val style: HubStyle,
    val items: List<MediaItem>,
    val label: HubLabel? = null,
)

/** 客户端自己生成的行，标题由界面按当前语言显示（服务器模块不依赖 Android 资源） */
sealed interface HubLabel {
    data object ContinueWatching : HubLabel
    data object NextUp : HubLabel
    data class RecentlyAdded(val library: String) : HubLabel
    /** 搜索结果按类型（和所在媒体库）分行 */
    data class SearchResults(val type: MediaType, val library: String?) : HubLabel
}

data class Page<T>(
    val items: List<T>,
    val offset: Int,
    val total: Int,
) {
    val hasMore: Boolean get() = offset + items.size < total
}

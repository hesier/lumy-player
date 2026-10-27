package com.hesier.lumy.core

/** 片头 / 片尾标记，由服务端分析得出 */
data class Marker(
    val type: MarkerType,
    val startMs: Long,
    val endMs: Long,
)

enum class MarkerType { INTRO, CREDITS }

data class Chapter(
    val index: Int,
    val title: String?,
    val startMs: Long,
    val endMs: Long,
    /** 章节缩略图引用，用 [MediaServer.imageUrl] 转换 */
    val thumb: String?,
)

enum class SortOrder { TITLE, ADDED, RELEASED, RATING, LAST_VIEWED }

enum class WatchFilter { ALL, UNWATCHED, WATCHED }

/** 分辨率筛选只对电影库有意义 */
enum class ResolutionFilter { ALL, UHD_4K, FHD_1080, HD_720, SD }

data class LibraryQuery(
    val sort: SortOrder = SortOrder.TITLE,
    val watch: WatchFilter = WatchFilter.ALL,
    val genreId: String? = null,
    val resolution: ResolutionFilter = ResolutionFilter.ALL,
) {
    val isDefault: Boolean get() = this == LibraryQuery()
}

data class FilterOption(val id: String, val title: String)

/** 按首字符分组的条目数，用于快速跳转 */
data class LetterBucket(val label: String, val count: Int)

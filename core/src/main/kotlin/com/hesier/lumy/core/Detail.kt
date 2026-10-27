package com.hesier.lumy.core

/** 详情页需要的完整信息 */
data class MediaDetail(
    val item: MediaItem,
    val tagline: String? = null,
    val contentRating: String? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    /** 演员，type 为 PERSON：id 是演员标识，summary 是饰演的角色 */
    val people: List<MediaItem> = emptyList(),
    /** 所属媒体库，按演员筛选作品时用 */
    val libraryId: String? = null,
    val studio: String? = null,
    /** 同一部影片的多个文件（如 4K 和 1080p 两个版本），电影和剧集才有 */
    val versions: List<MediaVersion> = emptyList(),
    /** 剧：下一集要看的；电影/剧集为空 */
    val onDeck: MediaItem? = null,
    val markers: List<Marker> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
)

/** 一个可播放的版本（源文件） */
data class MediaVersion(
    val id: String,
    /** 在服务端 Media 列表里的下标，转码时用 */
    val index: Int,
    val container: String?,
    val bitrateKbps: Int?,
    val video: VideoStream?,
    val audio: List<AudioStream>,
    val subtitles: List<SubtitleStream>,
    /** 一部片拆成多个文件（CD1/CD2）时按顺序排列；音轨/字幕信息取自第一段 */
    val parts: List<MediaPart>,
) {
    val part: MediaPart get() = parts.first()

    /** 每段在整部片中的起始时间 */
    val partStartsMs: List<Long>
        get() = parts.runningFold(0L) { acc, p -> acc + (p.durationMs ?: 0) }.dropLast(1)

    /**
     * 绝对时间落在第几段。离段尾不到 3 秒算作下一段的开头：
     * 别的客户端常把进度停在第一段末尾（差 1 毫秒），从那里续播会立刻结束。
     */
    fun partAt(positionMs: Long): Int {
        val starts = partStartsMs
        val i = starts.indexOfLast { positionMs >= it }.coerceAtLeast(0)
        return if (i + 1 < starts.size && positionMs >= starts[i + 1] - PART_END_SLACK_MS) i + 1 else i
    }

    /** 段内位置，不会小于 0 */
    fun localOffset(positionMs: Long, part: Int): Long = (positionMs - partStartsMs[part]).coerceAtLeast(0)

    private companion object {
        const val PART_END_SLACK_MS = 3_000L
    }

    /** 例如 “4K · HEVC · HDR10 · TrueHD 7.1” */
    val summary: String
        get() = listOfNotNull(
            video?.resolutionLabel,
            video?.codec?.uppercase(),
            video?.hdr?.label,
            audio.firstOrNull { it.selected }?.let { a -> listOfNotNull(a.codec.uppercase(), a.channelsLabel).joinToString(" ") }
                ?: audio.firstOrNull()?.codec?.uppercase(),
        ).joinToString(" · ")
}

data class MediaPart(
    val id: String,
    /** 服务端上的文件地址（相对路径），直接播放时用 */
    val key: String,
    val container: String?,
    val sizeBytes: Long?,
    val durationMs: Long?,
    /** 服务端上的文件路径，详情页显示文件名用 */
    val file: String? = null,
)

enum class HdrType(val label: String?) {
    NONE(null), HDR10("HDR10"), HDR10_PLUS("HDR10+"), HLG("HLG"), DOLBY_VISION("Dolby Vision"),
}

data class VideoStream(
    val codec: String,
    val width: Int?,
    val height: Int?,
    val frameRate: Float?,
    val bitDepth: Int?,
    val profile: String?,
    val hdr: HdrType,
    /** 杜比视界 profile（5、7、8…），没有为 null */
    val doviProfile: Int?,
    val bitrateKbps: Int? = null,
    val title: String? = null,
    val language: String? = null,
) {
    val resolutionLabel: String?
        get() {
            val w = width ?: return null
            val h = height ?: return null
            return when {
                w >= 3800 || h >= 2100 -> "4K"
                w >= 1900 || h >= 1060 -> "1080p"
                w >= 1260 || h >= 700 -> "720p"
                else -> "${h}p"
            }
        }
}

data class AudioStream(
    val id: String,
    /** 在容器里的流序号（0 起），用来对应播放器里的轨道 */
    val index: Int? = null,
    val codec: String,
    val channels: Int?,
    val language: String?,
    val title: String,
    val selected: Boolean,
    val bitrateKbps: Int? = null,
    val sampleRate: Int? = null,
) {
    val channelsLabel: String?
        get() = when (channels) {
            null -> null
            1 -> "Mono"
            2 -> "2.0"
            6 -> "5.1"
            8 -> "7.1"
            else -> "${channels}ch"
        }
}

data class SubtitleStream(
    val id: String,
    /** 在容器里的流序号（0 起）；外挂字幕为 null */
    val index: Int? = null,
    val codec: String,
    val language: String?,
    val title: String,
    val forced: Boolean,
    val selected: Boolean,
    /** 外挂字幕文件的地址（相对路径）；内封字幕为 null */
    val key: String?,
) {
    val external: Boolean get() = key != null
}

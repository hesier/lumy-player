package com.hesier.lumy.core

/** 播放方式：直接播放源文件，或让服务端转码 */
sealed interface PlaybackMode {
    data object Direct : PlaybackMode

    /**
     * 服务端转码为 HLS。能直通的轨道（视频/音频）服务端会直接复制，不会重新编码。
     * @param maxBitrateKbps 为 null 表示不限码率（只因格式不支持而转码）
     */
    data class Transcode(
        val maxBitrateKbps: Int? = null,
        val maxHeight: Int? = null,
        /** 把字幕烧进画面，用于客户端无法渲染的字幕（如外挂 PGS） */
        val burnSubtitleId: String? = null,
        /**
         * 允许服务端原样复制视频流（只换封装）。本机解不了这路视频、限码率或烧录字幕时必须为 false，
         * 否则服务端会把同样解不了的视频原样送回来
         */
        val copyVideo: Boolean = true,
        /**
         * 输出 SDR：只允许 H.264（8bit），服务端对 HDR 片源做色调映射。电视不支持 HDR 时用，
         * 需要服务端开启色调映射，否则画面还是发灰。为 true 时 [copyVideo] 必须为 false
         */
        val sdr: Boolean = false,
    ) : PlaybackMode
}

data class PlaybackRequest(
    val item: MediaItem,
    val version: MediaVersion,
    val mode: PlaybackMode,
    val startMs: Long,
    /** 一次播放会话的唯一 id，服务端用它关联转码任务和进度 */
    val sessionId: String,
    /** 选中的音轨 / 字幕（null 为关闭）。Plex 转码按服务端记住的选择输出，Jellyfin / Emby 要在地址里指定 */
    val audioStreamId: String? = null,
    val subtitleStreamId: String? = null,
)

/**
 * 一段文件一个地址；单文件影片只有一个。
 * 播放从 [startPart] 开始，该段内的起始位置为 [startOffsetMs]（转码流已从该位置开始转码）。
 */
data class PlaybackSource(
    val urls: List<String>,
    val isHls: Boolean,
    val externalSubtitles: List<ExternalSubtitle>,
    val startPart: Int,
    val startOffsetMs: Long,
)

data class ExternalSubtitle(
    val streamId: String,
    val url: String,
    /** 如 srt / ass / vtt */
    val codec: String,
    val language: String?,
    val label: String,
)

enum class PlaybackState { PLAYING, PAUSED, BUFFERING, STOPPED }

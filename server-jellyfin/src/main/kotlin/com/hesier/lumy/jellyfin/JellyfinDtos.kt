package com.hesier.lumy.jellyfin

import kotlinx.serialization.Serializable

// 字段名按 JellyfinClient.json 的规则转成大驼峰（id → Id）

@Serializable
data class PublicSystemInfo(
    val id: String,
    val serverName: String = "",
    val version: String = "",
    /** Jellyfin 是 “Jellyfin Server”，Emby 没有这个字段 */
    val productName: String? = null,
)

@Serializable
data class AuthenticateRequest(val username: String, val pw: String)

@Serializable
data class AuthenticationResult(
    val accessToken: String,
    val user: UserDto,
    val serverId: String? = null,
)

@Serializable
data class UserDto(val id: String, val name: String = "")

@Serializable
data class ItemsResult(
    val items: List<BaseItem> = emptyList(),
    val totalRecordCount: Int? = null,
)

@Serializable
data class UserData(
    val playbackPositionTicks: Long = 0,
    val played: Boolean = false,
    val unplayedItemCount: Int? = null,
)

/** 只取名字：Emby 的 Id 是数字，Jellyfin 是字符串，用不到就不解析 */
@Serializable
data class NameDto(val name: String = "")

@Serializable
data class PersonDto(
    val id: String,
    val name: String = "",
    val role: String? = null,
    /** Actor、Director、Writer… */
    val type: String = "",
    val primaryImageTag: String? = null,
)

@Serializable
data class ChapterDto(
    val startPositionTicks: Long = 0,
    val name: String? = null,
    val imageTag: String? = null,
    /** 只有 Emby 有：Chapter、IntroStart、IntroEnd、CreditsStart */
    val markerType: String? = null,
)

@Serializable
data class BaseItem(
    val id: String,
    val name: String = "",
    /** Movie、Series、Season、Episode、BoxSet、Playlist、CollectionFolder… */
    val type: String = "",
    /** 媒体库（视图）的类型：movies、tvshows、music… */
    val collectionType: String? = null,
    val mediaType: String? = null,
    val parentId: String? = null,
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonId: String? = null,
    val indexNumber: Int? = null,
    val parentIndexNumber: Int? = null,
    val productionYear: Int? = null,
    val overview: String? = null,
    val runTimeTicks: Long? = null,
    val imageTags: Map<String, String> = emptyMap(),
    val backdropImageTags: List<String> = emptyList(),
    val parentBackdropItemId: String? = null,
    val parentBackdropImageTags: List<String> = emptyList(),
    val seriesPrimaryImageTag: String? = null,
    val parentThumbItemId: String? = null,
    val parentThumbImageTag: String? = null,
    val userData: UserData? = null,
    val childCount: Int? = null,
    val recursiveItemCount: Int? = null,
    // ---- 以下只在详情里有 ----
    val taglines: List<String> = emptyList(),
    val officialRating: String? = null,
    val communityRating: Double? = null,
    val genres: List<String> = emptyList(),
    val people: List<PersonDto> = emptyList(),
    val studios: List<NameDto> = emptyList(),
    val mediaSources: List<MediaSourceDto> = emptyList(),
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
data class MediaSourceDto(
    val id: String,
    val name: String? = null,
    /** 可能是逗号分隔的多个名字（mov,mp4,m4a…），取第一个 */
    val container: String? = null,
    val size: Long? = null,
    val bitrate: Int? = null,
    val path: String? = null,
    val runTimeTicks: Long? = null,
    val mediaStreams: List<MediaStreamDto> = emptyList(),
    val defaultAudioStreamIndex: Int? = null,
    val defaultSubtitleStreamIndex: Int? = null,
)

@Serializable
data class MediaStreamDto(
    /** Video、Audio、Subtitle、EmbeddedImage */
    val type: String = "",
    val index: Int = 0,
    val codec: String? = null,
    val language: String? = null,
    val displayTitle: String? = null,
    val title: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val isExternal: Boolean = false,
    val channels: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitDepth: Int? = null,
    val realFrameRate: Float? = null,
    val averageFrameRate: Float? = null,
    val profile: String? = null,
    /** Jellyfin：SDR、HDR10、HDR10Plus、HLG、DOVI、DOVIWithHDR10… */
    val videoRangeType: String? = null,
    val videoRange: String? = null,
    val dvProfile: Int? = null,
    /** Emby：DolbyVision 等，子类型如 DoviProfile81 */
    val extendedVideoType: String? = null,
    val extendedVideoSubType: String? = null,
    /** bps */
    val bitRate: Int? = null,
    val sampleRate: Int? = null,
)

/** Jellyfin 10.10 起的片头 / 片尾片段 */
@Serializable
data class MediaSegmentsResult(val items: List<MediaSegmentDto> = emptyList())

@Serializable
data class MediaSegmentDto(
    /** Intro、Outro、Recap、Preview、Commercial */
    val type: String = "",
    val startTicks: Long = 0,
    val endTicks: Long = 0,
)

@Serializable
data class PlaybackReport(
    val itemId: String,
    val mediaSourceId: String,
    val playSessionId: String,
    val positionTicks: Long,
    val isPaused: Boolean,
    val playMethod: String,
    val audioStreamIndex: Int? = null,
    /** -1 表示关闭字幕 */
    val subtitleStreamIndex: Int? = null,
    val canSeek: Boolean = true,
)

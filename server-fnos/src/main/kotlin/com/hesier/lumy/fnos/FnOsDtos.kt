package com.hesier.lumy.fnos

import kotlinx.serialization.Serializable

// FnOsClient.json converts property names to PascalCase.

@Serializable
data class PublicSystemInfo(
    val id: String,
    val serverName: String = "",
    val version: String = "",
    /** Optional identification from the compatibility endpoint. */
    val productName: String? = null,
    val operatingSystem: String? = null,
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
    val lastPlayedDate: String? = null,
)

/** Only names are needed; compatibility IDs may have different wire types. */
@Serializable
data class NameDto(val name: String = "")

@Serializable
data class PersonDto(
    val id: String,
    val name: String = "",
    val role: String? = null,
    /** Actor, Director, Writer, etc. */
    val type: String = "",
    val primaryImageTag: String? = null,
)

@Serializable
data class ChapterDto(
    val startPositionTicks: Long = 0,
    val name: String? = null,
    val imageTag: String? = null,
    /** Compatibility chapter markers: Chapter, IntroStart, IntroEnd, CreditsStart. */
    val markerType: String? = null,
)

@Serializable
data class BaseItem(
    val id: String,
    val name: String = "",
    /** Movie, Series, Season, Episode, BoxSet, Playlist, CollectionFolder, etc. */
    val type: String = "",
    /** View collection type: movies, tvshows, music, etc. */
    val collectionType: String? = null,
    val mediaType: String? = null,
    val parentId: String? = null,
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonId: String? = null,
    val indexNumber: Int? = null,
    val parentIndexNumber: Int? = null,
    val productionYear: Int? = null,
    val sortName: String? = null,
    val dateCreated: String? = null,
    val premiereDate: String? = null,
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
    // Detail-only fields.
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
    /** May contain comma-separated container names; mapping uses the first. */
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
    /** Video, Audio, Subtitle, EmbeddedImage. */
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
    /** Compatibility range: SDR, HDR10, HDR10Plus, HLG, DOVI, etc. */
    val videoRangeType: String? = null,
    val videoRange: String? = null,
    val dvProfile: Int? = null,
    /** Alternate range metadata, such as DolbyVision and DoviProfile81. */
    val extendedVideoType: String? = null,
    val extendedVideoSubType: String? = null,
    /** bps */
    val bitRate: Int? = null,
    val sampleRate: Int? = null,
)

/** Compatibility segment metadata retained for detail mapping. */
@Serializable
data class MediaSegmentsResult(val items: List<MediaSegmentDto> = emptyList())

@Serializable
data class MediaSegmentDto(
    /** Intro, Outro, Recap, Preview, Commercial. */
    val type: String = "",
    val startTicks: Long = 0,
    val endTicks: Long = 0,
)

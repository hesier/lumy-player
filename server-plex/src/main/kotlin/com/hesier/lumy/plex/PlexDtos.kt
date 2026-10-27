package com.hesier.lumy.plex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ---- plex.tv ----

@Serializable
data class PlexPin(
    val id: Long,
    val code: String,
    val authToken: String? = null,
    val expiresIn: Int? = null,
)

@Serializable
data class PlexResource(
    val name: String,
    val clientIdentifier: String,
    val provides: String = "",
    val owned: Boolean = false,
    val presence: Boolean = false,
    val accessToken: String? = null,
    val httpsRequired: Boolean = false,
    val publicAddressMatches: Boolean = false,
    val connections: List<PlexConnection> = emptyList(),
) {
    val isServer: Boolean get() = provides.split(',').any { it.trim() == "server" }
}

@Serializable
data class PlexConnection(
    val protocol: String = "http",
    val uri: String,
    /** IP 地址（plex.direct 域名里编码的那个） */
    val address: String? = null,
    val port: Int? = null,
    val local: Boolean = false,
    val relay: Boolean = false,
    @SerialName("IPv6") val ipv6: Boolean = false,
)

// ---- Plex Media Server ----

@Serializable
data class Envelope(
    @SerialName("MediaContainer") val container: MediaContainer,
)

@Serializable
data class MediaContainer(
    val machineIdentifier: String? = null,
    val size: Int = 0,
    val totalSize: Int? = null,
    val offset: Int? = null,
    @SerialName("Directory") val directories: List<PlexDirectory> = emptyList(),
    @SerialName("Metadata") val metadata: List<PlexMetadata> = emptyList(),
    @SerialName("Hub") val hubs: List<PlexHub> = emptyList(),
)

@Serializable
data class PlexDirectory(
    val key: String,
    val title: String,
    val type: String = "",
    /** firstCharacter 接口里是该字符下的条目数 */
    val size: Int = 0,
)

@Serializable
data class PlexHub(
    val hubIdentifier: String = "",
    val title: String = "",
    val type: String = "",
    @SerialName("Metadata") val metadata: List<PlexMetadata> = emptyList(),
)

@Serializable
data class PlexMetadata(
    val ratingKey: String,
    val type: String = "",
    val title: String = "",
    val grandparentTitle: String? = null,
    val parentTitle: String? = null,
    val index: Int? = null,
    val parentIndex: Int? = null,
    val year: Int? = null,
    val summary: String? = null,
    val thumb: String? = null,
    val art: String? = null,
    val parentThumb: String? = null,
    val grandparentThumb: String? = null,
    val grandparentArt: String? = null,
    val duration: Long? = null,
    val viewOffset: Long? = null,
    val viewCount: Int? = null,
    val leafCount: Int? = null,
    val viewedLeafCount: Int? = null,
    val parentRatingKey: String? = null,
    val grandparentRatingKey: String? = null,
    val childCount: Int? = null,
    /** 播放列表的拼图封面 */
    val composite: String? = null,
    val librarySectionID: Long? = null,
    val librarySectionTitle: String? = null,
    // ---- 以下只在详情接口里有 ----
    val tagline: String? = null,
    val contentRating: String? = null,
    val rating: Double? = null,
    val audienceRating: Double? = null,
    val studio: String? = null,
    @SerialName("Genre") val genres: List<PlexTag> = emptyList(),
    @SerialName("Director") val directors: List<PlexTag> = emptyList(),
    @SerialName("Role") val roles: List<PlexTag> = emptyList(),
    @SerialName("Media") val media: List<PlexMedia> = emptyList(),
    /** 服务端有时返回单个对象，有时返回数组，交给 [onDeckItems] 处理 */
    @SerialName("OnDeck") val onDeck: PlexOnDeck? = null,
    @SerialName("Marker") val markers: List<PlexMarker> = emptyList(),
    @SerialName("Chapter") val chapters: List<PlexChapter> = emptyList(),
)

@Serializable
data class PlexChapter(
    val index: Int = 0,
    val tag: String? = null,
    val startTimeOffset: Long = 0,
    val endTimeOffset: Long = 0,
    val thumb: String? = null,
)

@Serializable
data class PlexMarker(
    val type: String = "",
    val startTimeOffset: Long = 0,
    val endTimeOffset: Long = 0,
)

@Serializable
data class PlexTag(
    val tag: String = "",
    /** 演员的标识，用于 actor= 筛选 */
    val id: Long? = null,
    val role: String? = null,
    val thumb: String? = null,
)

@Serializable
data class PlexOnDeck(@SerialName("Metadata") val metadata: JsonElement? = null)

@Serializable
data class PlexMedia(
    val id: Long,
    val bitrate: Int? = null,
    val container: String? = null,
    val videoCodec: String? = null,
    @SerialName("Part") val parts: List<PlexPart> = emptyList(),
)

@Serializable
data class PlexPart(
    val id: Long,
    val key: String,
    val container: String? = null,
    val size: Long? = null,
    val duration: Long? = null,
    val file: String? = null,
    @SerialName("Stream") val streams: List<PlexStream> = emptyList(),
)

@Serializable
data class PlexStream(
    val id: Long,
    /** 1 视频，2 音频，3 字幕 */
    val streamType: Int,
    val codec: String = "",
    val index: Int? = null,
    val displayTitle: String? = null,
    val extendedDisplayTitle: String? = null,
    val title: String? = null,
    val language: String? = null,
    val languageCode: String? = null,
    /** BCP 47 标签，会带上简繁（zh-Hans / zh-Hant），languageCode 对简繁都是 chi */
    val languageTag: String? = null,
    val selected: Boolean = false,
    val default: Boolean = false,
    val forced: Boolean = false,
    /** 外挂字幕的下载地址 */
    val key: String? = null,
    val channels: Int? = null,
    val bitDepth: Int? = null,
    val colorTrc: String? = null,
    @SerialName("DOVIPresent") val doviPresent: Boolean = false,
    @SerialName("DOVIProfile") val doviProfile: Int? = null,
    val frameRate: Float? = null,
    val profile: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Int? = null,
    val samplingRate: Int? = null,
)

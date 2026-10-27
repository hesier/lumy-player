package com.hesier.lumy.core

import java.io.IOException

/**
 * 一台已连接、已认证的媒体服务器。登录和发现服务器的流程各家差异很大，
 * 不放在这里；它们的结果是一个 [ServerSession]，再由对应模块创建出 [MediaServer]。
 */
interface MediaServer {
    val session: ServerSession

    /** 保存的地址是否仍可用，启动时据此决定是否需要重新连接 */
    suspend fun isReachable(): Boolean

    suspend fun libraries(): List<Library>

    /** 首页的推荐行：继续观看、最近添加等 */
    suspend fun homeHubs(): List<Hub>

    suspend fun libraryItems(libraryId: String, offset: Int, limit: Int, query: LibraryQuery = LibraryQuery()): Page<MediaItem>

    /** 媒体库的类型（流派）列表 */
    suspend fun genres(libraryId: String): List<FilterOption>

    /** 按首字符分组的数量，顺序与按标题排序一致；服务端不支持时为空，界面不显示索引 */
    suspend fun letterIndex(libraryId: String, query: LibraryQuery): List<LetterBucket>

    /** 按类型分组的搜索结果 */
    suspend fun search(query: String): List<Hub>

    /** 标记已看/未看；对剧和季会作用到其下所有剧集 */
    suspend fun setWatched(id: String, watched: Boolean)

    /** 媒体库里的合集 */
    suspend fun collections(libraryId: String): List<MediaItem>

    /** 视频播放列表 */
    suspend fun playlists(): List<MediaItem>

    /** 合集或播放列表里的条目 */
    suspend fun containerItems(container: MediaItem): List<MediaItem>

    /** 更多类似 */
    suspend fun similar(id: String): List<MediaItem>

    /** 某个演员在该媒体库里的作品 */
    suspend fun personItems(libraryId: String, personId: String): List<MediaItem>

    /** 剧集的下一集（跨季），没有则为 null */
    suspend fun nextEpisode(episode: MediaItem): MediaItem?

    suspend fun detail(id: String): MediaDetail

    /** 剧的各季，或季的各集 */
    suspend fun children(id: String): List<MediaItem>

    /** 生成播放地址。转码会话在 [stopPlayback] 前一直占用服务器资源 */
    fun playbackSource(request: PlaybackRequest): PlaybackSource

    /** 播放进度上报；停止时服务端会据此标记已看 */
    suspend fun reportProgress(request: PlaybackRequest, state: PlaybackState, positionMs: Long, durationMs: Long)

    suspend fun stopPlayback(request: PlaybackRequest)

    /** 记住用户选的音轨/字幕，下次播放默认选中（subtitleId 为 null 表示关闭字幕）；服务端不支持时什么也不做 */
    suspend fun saveStreamSelection(version: MediaVersion, audioId: String?, subtitleId: String?)

    /** 播放请求需要附带的 HTTP 头（身份、token） */
    fun playbackHeaders(): Map<String, String>

    /** 把 [MediaItem] 里的图片引用转成按尺寸缩放后的完整地址 */
    fun imageUrl(ref: String, width: Int, height: Int): String
}

/** 可持久化的连接信息，重启 App 后据此重建 [MediaServer] */
data class ServerSession(
    val kind: ServerKind,
    val serverId: String,
    val name: String,
    val baseUrl: String,
    val token: String,
    /** Jellyfin / Emby 的接口按用户区分，需要用户 id；Plex 为 null */
    val userId: String? = null,
    /**
     * 登录时用的设备标识。Jellyfin 的 token 绑定在设备上，同一设备再次登录会让旧 token 失效，
     * 所以每次登录用各自的标识；为 null 时用客户端默认的
     */
    val deviceId: String? = null,
)

enum class ServerKind { PLEX, JELLYFIN, EMBY }

/** 服务端返回了错误状态码（区别于网络不通），这类错误不需要重新探测地址 */
open class HttpStatusException(val code: Int, message: String) : IOException(message)

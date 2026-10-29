package com.hesier.lumy.plex

import com.hesier.lumy.core.Hub
import com.hesier.lumy.core.HubLabel
import com.hesier.lumy.core.HubStyle
import com.hesier.lumy.core.ExternalSubtitle
import com.hesier.lumy.core.FilterOption
import com.hesier.lumy.core.LetterBucket
import com.hesier.lumy.core.Library
import com.hesier.lumy.core.LibraryQuery
import com.hesier.lumy.core.ResolutionFilter
import com.hesier.lumy.core.SortOrder
import com.hesier.lumy.core.WatchFilter
import com.hesier.lumy.core.MediaDetail
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.Page
import com.hesier.lumy.core.PlaybackMode
import com.hesier.lumy.core.PlaybackRequest
import com.hesier.lumy.core.PlaybackSource
import com.hesier.lumy.core.PlaybackState
import com.hesier.lumy.core.ServerSession
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody

class PlexMediaServer(
    override val session: ServerSession,
    private val client: PlexClient,
) : MediaServer {

    private val resolver by lazy { PlexConnectionResolver(client) }

    override suspend fun isReachable(): Boolean = resolver.isReachable(session)

    override suspend fun libraries(): List<Library> =
        get("/library/sections").directories
            .map { Library(id = it.key, title = it.title, type = mediaType(it.type)) }
            // 只展示视频类媒体库，音乐和照片暂不支持
            .filter { it.type == MediaType.MOVIE || it.type == MediaType.SHOW }

    override suspend fun homeHubs(): List<Hub> {
        val continueWatching = runCatching { get("/hubs/continueWatching").hubs }.getOrDefault(emptyList())
        val promoted = get("/hubs", "count" to "20").hubs
        // /hubs 里通常也带一个“继续观看”，标识和 /hubs/continueWatching 不同，按类型和标题去重
        val rest = if (continueWatching.any { it.metadata.isNotEmpty() }) promoted.filterNot { it.isContinueHub() } else promoted
        return (continueWatching + rest)
            .distinctBy { it.hubIdentifier }
            .distinctBy { it.title }
            .mapNotNull { it.toHub() }
    }

    override suspend fun libraryItems(libraryId: String, offset: Int, limit: Int, query: LibraryQuery): Page<MediaItem> {
        val container = get(
            "/library/sections/$libraryId/all",
            *query.params(),
            "X-Plex-Container-Start" to offset.toString(),
            "X-Plex-Container-Size" to limit.toString(),
        )
        val items = container.metadata.map { it.toItem() }
        return Page(items = items, offset = offset, total = container.totalSize ?: (offset + items.size))
    }

    override suspend fun genres(libraryId: String): List<FilterOption> =
        get("/library/sections/$libraryId/genre").directories.map { FilterOption(it.key, it.title) }

    override suspend fun letterIndex(libraryId: String, query: LibraryQuery): List<LetterBucket> =
        get("/library/sections/$libraryId/firstCharacter", *query.filterParams())
            .directories.map { LetterBucket(it.title, it.size) }

    /**
     * 按“类型 + 所在媒体库”分行，行标题用媒体库名（动漫 / 电视剧 / 综艺…）。
     * 不用 Plex 自带的行标题：它把 Shows 翻译成了“综艺秀”，所有剧都归到这一行。
     */
    override suspend fun search(query: String): List<Hub> =
        get("/hubs/search", "query" to query, "limit" to "20").hubs
            .filter { it.type in SEARCHABLE_TYPES }
            .flatMap { hub ->
                hub.metadata.groupBy { it.librarySectionTitle }.map { (library, items) ->
                    val title = searchRowTitle(hub.type, library)
                    PlexHub(hubIdentifier = "search.${hub.type}.${library.orEmpty()}", title = title, type = hub.type, metadata = items)
                        .toHub()?.copy(label = HubLabel.SearchResults(mediaType(hub.type), library?.takeIf { it.isNotBlank() }))
                }
            }
            .filterNotNull()

    override suspend fun setWatched(id: String, watched: Boolean) {
        val b = url(if (watched) "/:/scrobble" else "/:/unscrobble")
            .addQueryParameter("identifier", "com.plexapp.plugins.library")
            .addQueryParameter("key", id)
        client.execute(client.request(b.build().toString(), session.token).build())
    }

    override suspend fun collections(libraryId: String): List<MediaItem> =
        get("/library/sections/$libraryId/collections").metadata.map { it.toItem() }

    override suspend fun playlists(): List<MediaItem> =
        get("/playlists", "playlistType" to "video").metadata.map { it.toItem() }

    override suspend fun containerItems(container: MediaItem): List<MediaItem> = when (container.type) {
        MediaType.PLAYLIST -> get("/playlists/${container.id}/items")
        else -> get("/library/collections/${container.id}/children")
    }.metadata.map { it.toItem() }

    override suspend fun similar(id: String): List<MediaItem> =
        get("/library/metadata/$id/similar", "count" to "20").metadata.map { it.toItem() }

    override suspend fun personItems(libraryId: String, personId: String): List<MediaItem> =
        get("/library/sections/$libraryId/all", "actor" to personId).metadata.map { it.toItem() }

    override suspend fun nextEpisode(episode: MediaItem): MediaItem? {
        val seasonId = episode.parentId ?: return null
        val episodes = children(seasonId)
        val i = episodes.indexOfFirst { it.id == episode.id }
        if (i >= 0 && i + 1 < episodes.size) return episodes[i + 1]
        // 本季最后一集：找下一季的第一集
        val showId = episode.grandparentId ?: return null
        val seasons = children(showId)
        val s = seasons.indexOfFirst { it.id == seasonId }
        val nextSeason = seasons.getOrNull(s + 1) ?: return null
        return children(nextSeason.id).firstOrNull()
    }

    override suspend fun detail(id: String): MediaDetail {
        val meta = get("/library/metadata/$id", "includeOnDeck" to "1", "includeMarkers" to "1", "includeChapters" to "1").metadata.firstOrNull()
            ?: throw PlexHttpException(404, "Item $id not found")
        return meta.toDetail(client.json)
    }

    override suspend fun children(id: String): List<MediaItem> =
        get("/library/metadata/$id/children").metadata.map { it.toItem() }

    override suspend fun playbackSource(request: PlaybackRequest): PlaybackSource {
        val version = request.version
        val externalSubs = version.subtitles.filter { it.external }.map {
            ExternalSubtitle(
                streamId = it.id,
                url = url(it.key!!).build().toString(),
                codec = it.codec,
                language = it.language,
                label = it.title,
            )
        }
        val startPart = version.partAt(request.startMs)
        val startOffset = version.localOffset(request.startMs, startPart)
        return when (val mode = request.mode) {
            PlaybackMode.Direct -> PlaybackSource(
                urls = version.parts.map { url(it.key).build().toString() },
                isHls = false,
                externalSubtitles = externalSubs,
                startPart = startPart,
                startOffsetMs = startOffset,
            )
            is PlaybackMode.Transcode -> PlaybackSource(
                urls = version.parts.indices.map { i ->
                    val url = transcodeUrl(request, mode, i, offsetMs = if (i == startPart) startOffset else 0)
                    // 先请求一次 decision：直接请求 start.m3u8 时服务端会沿用这台设备上次的决定，
                    // 实测切换字幕后烧录的还是旧字幕（会话信息里显示的却是新字幕）
                    runCatching { client.execute(client.request(url.replace("/start.m3u8?", "/decision?"), session.token).build()) }
                    url
                },
                isHls = true,
                // 烧录字幕时外挂字幕就不需要了
                externalSubtitles = if (mode.burnSubtitleId != null) emptyList() else externalSubs,
                startPart = startPart,
                startOffsetMs = startOffset,
            )
        }
    }

    /** 每段一个转码会话：播放器会提前准备下一段，共用会话会互相打断 */
    private fun sessionFor(request: PlaybackRequest, part: Int) =
        if (request.version.parts.size == 1) request.sessionId else "${request.sessionId}-$part"

    /**
     * Plex 通用转码接口。directStream=1 让服务端在格式允许时只换封装、不重新编码视频，
     * 这样 4K 片源在客户端只是“容器不支持”时也不会占用服务器 CPU；本机解不了视频时（copyVideo=false）必须关掉。
     */
    private fun transcodeUrl(request: PlaybackRequest, mode: PlaybackMode.Transcode, part: Int, offsetMs: Long): String {
        val session = sessionFor(request, part)
        val b = url("/video/:/transcode/universal/start.m3u8")
            .addQueryParameter("path", "/library/metadata/${request.item.id}")
            .addQueryParameter("mediaIndex", request.version.index.toString())
            .addQueryParameter("partIndex", part.toString())
            .addQueryParameter("protocol", "hls")
            .addQueryParameter("fastSeek", "1")
            .addQueryParameter("directPlay", "0")
            .addQueryParameter("directStream", if (mode.copyVideo) "1" else "0")
            .addQueryParameter("directStreamAudio", "1")
            .addQueryParameter("offset", (offsetMs / 1000).toString())
            // 不能再带 X-Plex-Session-Identifier 参数：实测服务端会直接返回 400
            .addQueryParameter("session", session)
            .addQueryParameter("location", "lan")
            .addQueryParameter("subtitles", if (mode.burnSubtitleId != null) "burn" else "none")
            .addQueryParameter("subtitleSize", "100")
            .addQueryParameter("X-Plex-Client-Profile-Extra", if (mode.sdr) CLIENT_PROFILE_EXTRA_SDR else CLIENT_PROFILE_EXTRA)
        mode.maxBitrateKbps?.let {
            b.addQueryParameter("maxVideoBitrate", it.toString())
            b.addQueryParameter("videoQuality", "100")
        }
        mode.maxHeight?.let { h -> b.addQueryParameter("videoResolution", "${h * 16 / 9}x$h") }
        client.identityQuery().forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build().toString()
    }

    override suspend fun reportProgress(request: PlaybackRequest, state: PlaybackState, positionMs: Long, durationMs: Long) {
        val b = url("/:/timeline")
            .addQueryParameter("ratingKey", request.item.id)
            .addQueryParameter("key", "/library/metadata/${request.item.id}")
            .addQueryParameter("state", state.name.lowercase())
            .addQueryParameter("time", positionMs.toString())
            .addQueryParameter("duration", durationMs.toString())
            .addQueryParameter("context", "library")
        client.execute(
            client.request(b.build().toString(), session.token)
                .header("X-Plex-Session-Identifier", request.sessionId)
                .build(),
        )
    }

    override suspend fun stopPlayback(request: PlaybackRequest) {
        if (request.mode !is PlaybackMode.Transcode) return
        request.version.parts.indices.forEach { i ->
            val b = url("/video/:/transcode/universal/stop").addQueryParameter("session", sessionFor(request, i))
            runCatching { client.execute(client.request(b.build().toString(), session.token).build()) }
        }
    }

    override suspend fun saveStreamSelection(version: MediaVersion, audioId: String?, subtitleId: String?) {
        val b = url("/library/parts/${version.part.id}")
            .addQueryParameter("allParts", "1")
            // subtitleStreamID=0 表示关闭字幕
            .addQueryParameter("subtitleStreamID", subtitleId ?: "0")
        audioId?.let { b.addQueryParameter("audioStreamID", it) }
        client.execute(
            client.request(b.build().toString(), session.token).put(ByteArray(0).toRequestBody()).build(),
        )
    }

    override fun playbackHeaders(): Map<String, String> = client.headers(session.token)

    override fun imageUrl(ref: String, width: Int, height: Int): String =
        "${session.baseUrl}/photo/:/transcode".toHttpUrl().newBuilder()
            .addQueryParameter("width", width.toString())
            .addQueryParameter("height", height.toString())
            .addQueryParameter("minSize", "1")
            .addQueryParameter("upscale", "1")
            .addQueryParameter("url", ref)
            .addQueryParameter("X-Plex-Token", session.token)
            .build()
            .toString()

    private fun url(path: String): HttpUrl.Builder = (session.baseUrl + path).toHttpUrl().newBuilder()

    private suspend fun get(path: String, vararg query: Pair<String, String>): MediaContainer {
        val url = (session.baseUrl + path).toHttpUrl().newBuilder()
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        return client.fetch<Envelope>(client.request(url.toString(), session.token).build()).container
    }
}

private fun PlexHub.isContinueHub(): Boolean =
    listOf("continue", "ondeck", "inprogress").any { hubIdentifier.contains(it, ignoreCase = true) }

private val SEARCHABLE_TYPES = setOf("movie", "show", "season", "episode")

internal fun searchRowTitle(type: String, library: String?): String {
    val lib = library?.takeIf { it.isNotBlank() }
    return when (type) {
        "movie" -> lib ?: "Movies"
        "show" -> lib ?: "TV Shows"
        "season" -> lib?.let { "$it · Seasons" } ?: "Seasons"
        "episode" -> lib?.let { "$it · Episodes" } ?: "Episodes"
        else -> lib ?: type
    }
}

/** 筛选条件（firstCharacter 接口也要带上，跳转位置才对得上） */
internal fun LibraryQuery.filterParams(): Array<Pair<String, String>> = buildList {
    when (watch) {
        WatchFilter.UNWATCHED -> add("unwatched" to "1")
        WatchFilter.WATCHED -> add("unwatched" to "0")
        WatchFilter.ALL -> Unit
    }
    genreId?.let { add("genre" to it) }
    when (resolution) {
        ResolutionFilter.UHD_4K -> add("resolution" to "4k")
        ResolutionFilter.FHD_1080 -> add("resolution" to "1080")
        ResolutionFilter.HD_720 -> add("resolution" to "720")
        ResolutionFilter.SD -> add("resolution" to "sd")
        ResolutionFilter.ALL -> Unit
    }
}.toTypedArray()

internal fun LibraryQuery.params(): Array<Pair<String, String>> {
    val sortParam = when (sort) {
        SortOrder.TITLE -> "titleSort"
        SortOrder.ADDED -> "addedAt:desc"
        SortOrder.RELEASED -> "originallyAvailableAt:desc"
        SortOrder.RATING -> "audienceRating:desc"
        SortOrder.LAST_VIEWED -> "lastViewedAt:desc"
    }
    return arrayOf("sort" to sortParam, *filterParams())
}

/**
 * 告诉服务端转码目标：HLS + TS 封装，视频 H.264/HEVC。
 * 只声明 audioCodec 不够，服务端仍会把音频转成 AAC 立体声；还要逐个声明可以原样保留的音频编码
 * 和声道上限，EAC3 7.1 / AC3 5.1 / DTS 才会被直接复制（实测服务端 decision 由 transcode 变为 copy）。
 * TrueHD 不能放进 TS，服务端会转成 EAC3。客户端有 FFmpeg，这些格式都能解码。
 */
private fun clientProfileExtra(videoCodecs: String) = listOf(
    "add-transcode-target(type=videoProfile&context=streaming&protocol=hls&container=mpegts" +
        "&videoCodec=$videoCodecs&audioCodec=aac,ac3,eac3,mp3,opus,flac,dca)",
    "add-transcode-target-audio-codec(type=videoProfile&context=streaming&protocol=hls&audioCodec=eac3)",
    "add-transcode-target-audio-codec(type=videoProfile&context=streaming&protocol=hls&audioCodec=ac3)",
    "add-transcode-target-audio-codec(type=videoProfile&context=streaming&protocol=hls&audioCodec=dca)",
    "add-limitation(scope=videoAudioCodec&scopeName=eac3&type=upperBound&name=audio.channels&value=8)",
    "add-limitation(scope=videoAudioCodec&scopeName=ac3&type=upperBound&name=audio.channels&value=6)",
    "add-limitation(scope=videoAudioCodec&scopeName=dca&type=upperBound&name=audio.channels&value=8)",
).joinToString("+")

private val CLIENT_PROFILE_EXTRA = clientProfileExtra("h264,hevc")

/** 只允许 H.264：服务端转码 HDR 片源时会做色调映射（需在服务端开启），输出 SDR */
private val CLIENT_PROFILE_EXTRA_SDR = clientProfileExtra("h264")

internal fun PlexHub.toHub(): Hub? {
    // 音乐和照片暂不支持：首页的“最近添加的音乐”、音频播放列表等去掉，整行都是就不显示
    val items = metadata.filter { it.isSupported() }.map { it.toItem() }
    if (items.isEmpty()) return null
    // 继续观看/待看用横版截图，其余（最近添加等）用竖版海报，和官方客户端一致
    val landscape = isContinueHub() || items.all { it.type == MediaType.EPISODE }
    return Hub(
        id = hubIdentifier,
        title = title,
        style = if (landscape) HubStyle.LANDSCAPE else HubStyle.POSTER,
        items = items,
    )
}

internal fun PlexMetadata.isSupported(): Boolean = when (mediaType(type)) {
    MediaType.MUSIC, MediaType.PHOTO -> false
    MediaType.PLAYLIST -> playlistType == null || playlistType == "video"
    else -> true
}

internal fun PlexMetadata.toItem(): MediaItem {
    val type = mediaType(type)
    val isEpisode = type == MediaType.EPISODE
    return MediaItem(
        id = ratingKey,
        type = type,
        title = title,
        parentId = parentRatingKey,
        grandparentId = grandparentRatingKey,
        showTitle = if (isEpisode) grandparentTitle else if (type == MediaType.SEASON) parentTitle else null,
        seasonNumber = if (isEpisode) parentIndex else if (type == MediaType.SEASON) index else null,
        episodeNumber = if (isEpisode) index else null,
        year = year,
        summary = summary,
        poster = when (type) {
            MediaType.EPISODE -> grandparentThumb ?: parentThumb ?: thumb
            else -> thumb ?: composite
        },
        thumb = if (isEpisode) thumb else art,
        backdrop = if (isEpisode) grandparentArt ?: art else art,
        durationMs = duration,
        viewOffsetMs = viewOffset,
        watched = when {
            leafCount != null -> viewedLeafCount != null && viewedLeafCount >= leafCount
            else -> (viewCount ?: 0) > 0
        },
        unwatchedCount = if (leafCount != null && type != MediaType.PLAYLIST) (leafCount - (viewedLeafCount ?: 0)).coerceAtLeast(0) else null,
        childCount = childCount ?: leafCount.takeIf { type == MediaType.PLAYLIST },
    )
}

internal fun mediaType(plexType: String): MediaType = when (plexType) {
    "movie" -> MediaType.MOVIE
    "show" -> MediaType.SHOW
    "season" -> MediaType.SEASON
    "episode" -> MediaType.EPISODE
    "artist", "album", "track" -> MediaType.MUSIC
    "photo" -> MediaType.PHOTO
    "collection" -> MediaType.COLLECTION
    "playlist" -> MediaType.PLAYLIST
    else -> MediaType.OTHER
}

package com.hesier.lumy.jellyfin

import com.hesier.lumy.core.ExternalSubtitle
import com.hesier.lumy.core.FilterOption
import com.hesier.lumy.core.Hub
import com.hesier.lumy.core.HubLabel
import com.hesier.lumy.core.HubStyle
import com.hesier.lumy.core.LetterBucket
import com.hesier.lumy.core.Library
import com.hesier.lumy.core.LibraryQuery
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
import com.hesier.lumy.core.ResolutionFilter
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import com.hesier.lumy.core.SortOrder
import com.hesier.lumy.core.WatchFilter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Jellyfin 和 Emby 的 [MediaServer] 实现，[ServerSession.kind] 区分两者。
 * 尽量只用两家、新旧版本都有的接口（/Items?UserId=…）；Jellyfin 10.9 把部分 /Users/{id}/… 接口
 * 挪了位置，这些用 [getFirst] 依次尝试。
 */
class JellyfinMediaServer(
    override val session: ServerSession,
    private val client: JellyfinClient,
) : MediaServer {

    private val kind = session.kind
    private val userId = session.userId ?: error("Jellyfin / Emby session has no user id")
    /** 旧版本保存的会话没有单独的设备标识，当时用的是客户端默认的 */
    private val deviceId = session.deviceId ?: client.info.deviceId

    /** 已经发过“开始播放”的会话，之后的上报走 Progress */
    private val startedSessions = Collections.synchronizedSet(HashSet<String>())

    override suspend fun isReachable(): Boolean {
        val http = client.http.newBuilder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        return runCatching {
            client.fetch<PublicSystemInfo>(client.request(url("System/Info/Public").build().toString(), kind, null).build(), http)
        }.getOrNull()?.id == session.serverId
    }

    override suspend fun libraries(): List<Library> {
        val views = if (kind == ServerKind.JELLYFIN) {
            getFirst<ItemsResult>(url("UserViews").addQueryParameter("userId", userId), url("Users/$userId/Views"))
        } else {
            get<ItemsResult>(url("Users/$userId/Views"))
        }
        // 只展示电影和剧集媒体库，音乐、照片等暂不支持
        return views.items.mapNotNull { v ->
            val type = when (v.collectionType) {
                "movies" -> MediaType.MOVIE
                "tvshows" -> MediaType.SHOW
                else -> return@mapNotNull null
            }
            Library(id = v.id, title = v.name, type = type)
        }
    }

    override suspend fun homeHubs(): List<Hub> = coroutineScope {
        val resume = async {
            runCatching {
                items(
                    "Recursive" to "true",
                    "Filters" to "IsResumable",
                    "MediaTypes" to "Video",
                    "SortBy" to "DatePlayed",
                    "SortOrder" to "Descending",
                    "Limit" to "20",
                )
            }.getOrDefault(emptyList())
        }
        val nextUp = async {
            runCatching {
                get<ItemsResult>(userUrl("Shows/NextUp").addQueryParameter("Limit", "20").withFields()).items.map { it.toItem() }
            }.getOrDefault(emptyList())
        }
        val latest = libraries().map { lib ->
            async {
                val list = runCatching { latestIn(lib) }.getOrDefault(emptyList())
                Hub(id = "latest.${lib.id}", title = "Recently Added · ${lib.title}", style = HubStyle.POSTER, items = list, label = HubLabel.RecentlyAdded(lib.title))
            }
        }
        val continueItems = resume.await()
        // “下一集”里已经出现在继续观看里的剧集去掉
        val upNext = nextUp.await().filterNot { n -> continueItems.any { it.id == n.id } }
        listOf(
            Hub("resume", "Continue Watching", HubStyle.LANDSCAPE, continueItems, HubLabel.ContinueWatching),
            Hub("nextup", "Next Up", HubStyle.LANDSCAPE, upNext, HubLabel.NextUp),
        ).plus(latest.awaitAll())
            .filter { it.items.isNotEmpty() }
    }

    /** 剧集库按“最近有新集”排序剧，和官方客户端的“最近添加”一致 */
    private suspend fun latestIn(lib: Library): List<MediaItem> = items(
        "ParentId" to lib.id,
        "Recursive" to "true",
        "IncludeItemTypes" to if (lib.type == MediaType.SHOW) "Series" else "Movie",
        "SortBy" to if (lib.type == MediaType.SHOW) "DateLastContentAdded,SortName" else "DateCreated,SortName",
        "SortOrder" to "Descending",
        "Limit" to "20",
    )

    override suspend fun libraryItems(libraryId: String, offset: Int, limit: Int, query: LibraryQuery): Page<MediaItem> {
        val result = get<ItemsResult>(
            userUrl("Items")
                .addAll(
                    "ParentId" to libraryId,
                    "Recursive" to "true",
                    "IncludeItemTypes" to "Movie,Series",
                    "StartIndex" to offset.toString(),
                    "Limit" to limit.toString(),
                    *query.params(),
                )
                .withFields(),
        )
        val items = result.items.map { it.toItem() }
        return Page(items = items, offset = offset, total = result.totalRecordCount ?: (offset + items.size))
    }

    override suspend fun genres(libraryId: String): List<FilterOption> =
        get<ItemsResult>(userUrl("Genres").addAll("ParentId" to libraryId, "SortBy" to "SortName")).items
            .map { FilterOption(it.id, it.name) }

    /** 没有按首字符统计的接口，不显示字母索引 */
    override suspend fun letterIndex(libraryId: String, query: LibraryQuery): List<LetterBucket> = emptyList()

    override suspend fun search(query: String): List<Hub> {
        val found = items(
            "SearchTerm" to query,
            "Recursive" to "true",
            "IncludeItemTypes" to "Movie,Series,Episode",
            "Limit" to "60",
        )
        return listOf(MediaType.MOVIE to "Movies", MediaType.SHOW to "TV Shows", MediaType.EPISODE to "Episodes").mapNotNull { (type, title) ->
            found.filter { it.type == type }.takeIf { it.isNotEmpty() }?.let {
                Hub(id = "search.$type", title = title, style = if (type == MediaType.EPISODE) HubStyle.LANDSCAPE else HubStyle.POSTER, items = it, label = HubLabel.SearchResults(type, null))
            }
        }
    }

    override suspend fun setWatched(id: String, watched: Boolean) {
        val modern = url("UserPlayedItems/$id").addQueryParameter("userId", userId)
        val legacy = url("Users/$userId/PlayedItems/$id")
        val candidates = if (kind == ServerKind.JELLYFIN) listOf(modern, legacy) else listOf(legacy)
        firstOk(candidates) { b ->
            val req = client.request(b.build().toString(), kind, session.token, deviceId)
            client.execute((if (watched) req.post(EMPTY_BODY) else req.delete()).build())
        }
    }

    /**
     * 合集（BoxSet）不在电影库里，不能按 ParentId 过滤。让服务端把这个库的条目按合集折叠
     * （CollapseBoxSetItems，Jellyfin / Emby 都支持），折叠出来的就是含有这个库内容的合集。
     * 先只取 id，再按 id 取完整信息，避免整个库的条目都带上图片和详情字段。
     */
    override suspend fun collections(libraryId: String): List<MediaItem> {
        val ids = get<ItemsResult>(
            userUrl("Items").addAll(
                "ParentId" to libraryId,
                "Recursive" to "true",
                "IncludeItemTypes" to "Movie,Series",
                "CollapseBoxSetItems" to "true",
                "EnableImages" to "false",
                "EnableUserData" to "false",
            ),
        ).items.filter { it.type == "BoxSet" }.map { it.id }
        if (ids.isEmpty()) return emptyList()
        return items("Ids" to ids.joinToString(","), "SortBy" to "SortName")
    }

    override suspend fun playlists(): List<MediaItem> =
        get<ItemsResult>(userUrl("Items").addAll("IncludeItemTypes" to "Playlist", "Recursive" to "true", "SortBy" to "SortName").withFields())
            .items.filter { it.mediaType != "Audio" }.map { it.toItem() }

    override suspend fun containerItems(container: MediaItem): List<MediaItem> = when (container.type) {
        MediaType.PLAYLIST -> get<ItemsResult>(userUrl("Playlists/${container.id}/Items").withFields()).items.map { it.toItem() }
        else -> items("ParentId" to container.id, "SortBy" to "PremiereDate,SortName")
    }

    override suspend fun similar(id: String): List<MediaItem> =
        get<ItemsResult>(userUrl("Items/$id/Similar").addQueryParameter("Limit", "20").withFields()).items.map { it.toItem() }

    /** 不限媒体库：服务端的演员 id 是全局的 */
    override suspend fun personItems(libraryId: String, personId: String): List<MediaItem> = items(
        "PersonIds" to personId,
        "Recursive" to "true",
        "IncludeItemTypes" to "Movie,Series",
        "SortBy" to "ProductionYear,SortName",
        "SortOrder" to "Descending",
    )

    override suspend fun nextEpisode(episode: MediaItem): MediaItem? {
        val seriesId = episode.grandparentId ?: return null
        val list = get<ItemsResult>(
            userUrl("Shows/$seriesId/Episodes").addAll("StartItemId" to episode.id, "Limit" to "2").withFields(),
        ).items
        return list.dropWhile { it.id != episode.id }.getOrNull(1)?.toItem()
    }

    override suspend fun detail(id: String): MediaDetail = coroutineScope {
        val segments = async { if (kind == ServerKind.JELLYFIN) mediaSegments(id) else emptyList() }
        val item = item(id, DETAIL_FIELDS) ?: throw JellyfinHttpException(404, "Item $id not found")
        val onDeck = if (item.type == "Series") {
            runCatching {
                get<ItemsResult>(userUrl("Shows/NextUp").addAll("SeriesId" to id, "Limit" to "1").withFields()).items.firstOrNull()?.toItem()
            }.getOrNull()
        } else {
            null
        }
        item.toDetail(segments.await(), onDeck)
    }

    /** 旧版本没有这个接口，失败就当没有标记 */
    private suspend fun mediaSegments(id: String): List<MediaSegmentDto> = runCatching {
        get<MediaSegmentsResult>(url("MediaSegments/$id")).items
    }.getOrDefault(emptyList())

    override suspend fun children(id: String): List<MediaItem> {
        val parent = item(id, "") ?: return emptyList()
        val b = when (parent.type) {
            "Series" -> userUrl("Shows/$id/Seasons")
            "Season" -> userUrl("Shows/${parent.seriesId ?: parent.parentId}/Episodes").addQueryParameter("SeasonId", id)
            else -> userUrl("Items").addAll("ParentId" to id, "SortBy" to "SortName")
        }
        return get<ItemsResult>(b.withFields()).items.map { it.toItem() }
    }

    override suspend fun playbackSource(request: PlaybackRequest): PlaybackSource {
        val version = request.version
        val externalSubs = version.subtitles.filter { it.external }.map {
            ExternalSubtitle(
                streamId = it.id,
                url = url(it.key!!).withToken().build().toString(),
                codec = it.codec,
                language = it.language,
                label = it.title,
            )
        }
        return when (val mode = request.mode) {
            PlaybackMode.Direct -> PlaybackSource(
                urls = listOf(
                    url("Videos/${request.item.id}/stream")
                        .addAll(
                            "Static" to "true",
                            "MediaSourceId" to version.id,
                            "PlaySessionId" to request.sessionId,
                            "DeviceId" to deviceId,
                        )
                        .withToken().build().toString(),
                ),
                isHls = false,
                externalSubtitles = externalSubs,
                startPart = 0,
                startOffsetMs = request.startMs,
            )
            is PlaybackMode.Transcode -> PlaybackSource(
                urls = listOf(transcodeUrl(request, mode)),
                isHls = true,
                // 烧录字幕时外挂字幕就不需要了
                externalSubtitles = if (mode.burnSubtitleId != null) emptyList() else externalSubs,
                startPart = 0,
                startOffsetMs = request.startMs,
            )
        }
    }

    /**
     * HLS 转码。[PlaybackMode.Transcode.copyVideo] 时允许直接复制视频流，只换封装，服务器几乎没有负担；
     * 本机解不了视频或限码率时必须重新编码。音频声明 AC3/EAC3 等，客户端有 FFmpeg，多声道也能原样保留。
     * 不带起播位置：服务端的播放列表总是覆盖整部影片，播放器就绪后自己 seek 过去。
     */
    private fun transcodeUrl(request: PlaybackRequest, mode: PlaybackMode.Transcode): String {
        val b = url("Videos/${request.item.id}/master.m3u8").addAll(
            "MediaSourceId" to request.version.id,
            "PlaySessionId" to request.sessionId,
            "DeviceId" to deviceId,
            // 只允许 H.264 时服务端会对 HDR 片源做色调映射（需在服务端开启）
            "VideoCodec" to if (mode.sdr) "h264" else "h264,hevc",
            "AudioCodec" to "aac,ac3,eac3,mp3,opus,flac",
            "TranscodingMaxAudioChannels" to "8",
            "SegmentContainer" to "ts",
            "BreakOnNonKeyFrames" to "true",
            "AllowVideoStreamCopy" to mode.copyVideo.toString(),
            "AllowAudioStreamCopy" to "true",
        )
        request.audioStreamId?.let { b.addQueryParameter("AudioStreamIndex", it) }
        mode.burnSubtitleId?.let {
            b.addQueryParameter("SubtitleStreamIndex", it)
            b.addQueryParameter("SubtitleMethod", "Encode")
        }
        // 不带码率时服务端按很低的默认值（640 kbps）转码，画面会缩到 416x234，所以总要给一个
        val kbps = mode.maxBitrateKbps ?: if (mode.copyVideo) COPY_MAX_KBPS else REENCODE_KBPS
        b.addQueryParameter("MaxStreamingBitrate", (kbps * 1000).toString())
        if (!mode.copyVideo) b.addQueryParameter("VideoBitrate", (kbps * 1000).toString())
        mode.maxHeight?.let {
            b.addQueryParameter("MaxHeight", it.toString())
            b.addQueryParameter("MaxWidth", (it * 16 / 9).toString())
        }
        return b.withToken().build().toString()
    }

    override suspend fun reportProgress(request: PlaybackRequest, state: PlaybackState, positionMs: Long, durationMs: Long) {
        val report = PlaybackReport(
            itemId = request.item.id,
            mediaSourceId = request.version.id,
            playSessionId = request.sessionId,
            positionTicks = positionMs * TICKS_PER_MS,
            isPaused = state == PlaybackState.PAUSED,
            playMethod = if (request.mode is PlaybackMode.Transcode) "Transcode" else "DirectPlay",
            audioStreamIndex = request.audioStreamId?.toIntOrNull(),
            // 上报选择后服务端会记住（用户开启了“记住音轨/字幕”时），下次详情里的默认流就是它
            subtitleStreamIndex = request.subtitleStreamId?.toIntOrNull() ?: -1,
        )
        val path = when {
            state == PlaybackState.STOPPED -> "Sessions/Playing/Stopped".also { startedSessions.remove(request.sessionId) }
            startedSessions.add(request.sessionId) -> "Sessions/Playing"
            else -> "Sessions/Playing/Progress"
        }
        val body = client.jsonBody(client.json.encodeToString(PlaybackReport.serializer(), report))
        client.execute(client.request(url(path).build().toString(), kind, session.token, deviceId).post(body).build())
    }

    /** 只结束转码任务；不能发不带位置的 Stopped，服务端会因此把影片标记为已看 */
    override suspend fun stopPlayback(request: PlaybackRequest) {
        if (request.mode !is PlaybackMode.Transcode) return
        val b = url("Videos/ActiveEncodings").addAll("DeviceId" to deviceId, "PlaySessionId" to request.sessionId)
        runCatching { client.execute(client.request(b.build().toString(), kind, session.token, deviceId).delete().build()) }
    }

    /** 服务端从播放上报里记住音轨 / 字幕，这里不需要单独保存 */
    override suspend fun saveStreamSelection(version: MediaVersion, audioId: String?, subtitleId: String?) = Unit

    override fun playbackHeaders(): Map<String, String> = client.headers(kind, session.token, deviceId)

    override fun imageUrl(ref: String, width: Int, height: Int): String =
        url(ref)
            .addQueryParameter("maxWidth", width.toString())
            .addQueryParameter("maxHeight", height.toString())
            .addQueryParameter("quality", "90")
            .build()
            .toString()

    // ---- 请求 ----

    private fun url(path: String): HttpUrl.Builder = "${session.baseUrl}/$path".toHttpUrl().newBuilder()

    private fun userUrl(path: String): HttpUrl.Builder = url(path).addQueryParameter("UserId", userId)

    /** HLS 子播放列表和外挂字幕不一定带请求头，地址里也放 token */
    private fun HttpUrl.Builder.withToken(): HttpUrl.Builder =
        addQueryParameter(if (kind == ServerKind.EMBY) "api_key" else "ApiKey", session.token)

    private fun HttpUrl.Builder.withFields(fields: String = LIST_FIELDS): HttpUrl.Builder = apply {
        if (fields.isNotEmpty()) addQueryParameter("Fields", fields)
        addQueryParameter("EnableImageTypes", "Primary,Backdrop,Thumb")
        addQueryParameter("ImageTypeLimit", "1")
    }

    private fun HttpUrl.Builder.addAll(vararg params: Pair<String, String>): HttpUrl.Builder = apply {
        params.forEach { (k, v) -> addQueryParameter(k, v) }
    }

    private suspend fun items(vararg params: Pair<String, String>): List<MediaItem> =
        get<ItemsResult>(userUrl("Items").addAll(*params).withFields()).items.map { it.toItem() }

    /** 按 id 取单个条目：/Items?Ids= 在各版本都有，/Items/{id} 只有 Jellyfin 10.9 以后才有 */
    private suspend fun item(id: String, fields: String): BaseItem? =
        get<ItemsResult>(userUrl("Items").addQueryParameter("Ids", id).withFields(fields)).items.firstOrNull()

    private suspend inline fun <reified T> get(b: HttpUrl.Builder): T =
        client.fetch(client.request(b.build().toString(), kind, session.token, deviceId).build())

    private suspend inline fun <reified T> getFirst(vararg candidates: HttpUrl.Builder): T =
        firstOk(candidates.toList()) { get<T>(it) }

    /** 依次尝试，只有 404 才换下一个 */
    private suspend fun <T> firstOk(candidates: List<HttpUrl.Builder>, block: suspend (HttpUrl.Builder) -> T): T {
        var last: JellyfinHttpException? = null
        for (b in candidates) {
            try {
                return block(b)
            } catch (e: JellyfinHttpException) {
                if (e.code != 404) throw e
                last = e
            }
        }
        throw last ?: JellyfinHttpException(404, "No route")
    }

    private companion object {
        val EMPTY_BODY = ByteArray(0).toRequestBody()
        /** 复制视频流时的码率上限，足够高，避免服务端因“超码率”改为重新编码 */
        const val COPY_MAX_KBPS = 200_000
        /** 不限画质但本机解不了、必须重新编码时的目标码率（1080p 高画质） */
        const val REENCODE_KBPS = 20_000
        const val LIST_FIELDS = "Overview,ChildCount,RecursiveItemCount,ParentId"
        const val DETAIL_FIELDS = "$LIST_FIELDS,Genres,People,Studios,Taglines,MediaSources,MediaStreams,Chapters"
    }
}

internal fun LibraryQuery.params(): Array<Pair<String, String>> = buildList {
    val (sortBy, order) = when (sort) {
        SortOrder.TITLE -> "SortName" to "Ascending"
        SortOrder.ADDED -> "DateCreated,SortName" to "Descending"
        SortOrder.RELEASED -> "PremiereDate,SortName" to "Descending"
        SortOrder.RATING -> "CommunityRating,SortName" to "Descending"
        SortOrder.LAST_VIEWED -> "DatePlayed,SortName" to "Descending"
    }
    add("SortBy" to sortBy)
    add("SortOrder" to order)
    when (watch) {
        WatchFilter.UNWATCHED -> add("Filters" to "IsUnplayed")
        WatchFilter.WATCHED -> add("Filters" to "IsPlayed")
        WatchFilter.ALL -> Unit
    }
    genreId?.let { add("GenreIds" to it) }
    // 和 VideoStream.resolutionLabel 的分界一致
    when (resolution) {
        ResolutionFilter.UHD_4K -> add("MinWidth" to "3800")
        ResolutionFilter.FHD_1080 -> addAll(listOf("MinWidth" to "1900", "MaxWidth" to "3799"))
        ResolutionFilter.HD_720 -> addAll(listOf("MinWidth" to "1260", "MaxWidth" to "1899"))
        ResolutionFilter.SD -> add("MaxWidth" to "1259")
        ResolutionFilter.ALL -> Unit
    }
}.toTypedArray()

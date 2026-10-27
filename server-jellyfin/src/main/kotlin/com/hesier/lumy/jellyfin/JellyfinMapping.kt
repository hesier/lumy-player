package com.hesier.lumy.jellyfin

import com.hesier.lumy.core.AudioStream
import com.hesier.lumy.core.Chapter
import com.hesier.lumy.core.HdrType
import com.hesier.lumy.core.Marker
import com.hesier.lumy.core.MarkerType
import com.hesier.lumy.core.MediaDetail
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaPart
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.SubtitleStream
import com.hesier.lumy.core.VideoStream

/** 服务端时间单位是 100 纳秒（tick） */
internal const val TICKS_PER_MS = 10_000L

internal fun Long.ticksToMs(): Long = this / TICKS_PER_MS

/**
 * 图片引用是相对路径（带 tag，图片换了 tag 会变，缓存自然失效），
 * 由 [JellyfinMediaServer.imageUrl] 补上服务器地址和尺寸。
 */
internal fun imageRef(itemId: String, type: String, tag: String, index: Int? = null): String =
    "Items/$itemId/Images/$type" + (index?.let { "/$it" } ?: "") + "?tag=$tag"

internal fun mediaType(type: String): MediaType = when (type) {
    "Movie" -> MediaType.MOVIE
    "Series" -> MediaType.SHOW
    "Season" -> MediaType.SEASON
    "Episode" -> MediaType.EPISODE
    "BoxSet" -> MediaType.COLLECTION
    "Playlist" -> MediaType.PLAYLIST
    "Person" -> MediaType.PERSON
    "MusicArtist", "MusicAlbum", "Audio" -> MediaType.MUSIC
    "Photo", "PhotoAlbum" -> MediaType.PHOTO
    else -> MediaType.OTHER
}

internal fun BaseItem.toItem(): MediaItem {
    val type = mediaType(type)
    val isEpisode = type == MediaType.EPISODE
    val primary = imageTags["Primary"]?.let { imageRef(id, "Primary", it) }
    val seriesPrimary = seriesPrimaryImageTag?.let { tag -> seriesId?.let { imageRef(it, "Primary", tag) } }
    val backdrop = backdropImageTags.firstOrNull()?.let { imageRef(id, "Backdrop", it, 0) }
        ?: parentBackdropImageTags.firstOrNull()?.let { tag -> parentBackdropItemId?.let { imageRef(it, "Backdrop", tag, 0) } }
    val landscape = imageTags["Thumb"]?.let { imageRef(id, "Thumb", it) }
        ?: parentThumbImageTag?.let { tag -> parentThumbItemId?.let { imageRef(it, "Thumb", tag) } }
    val data = userData
    return MediaItem(
        id = id,
        type = type,
        title = name,
        parentId = when (type) {
            MediaType.EPISODE -> seasonId ?: parentId
            MediaType.SEASON -> seriesId ?: parentId
            else -> parentId
        },
        grandparentId = if (isEpisode) seriesId else null,
        showTitle = if (isEpisode || type == MediaType.SEASON) seriesName else null,
        seasonNumber = if (isEpisode) parentIndexNumber else if (type == MediaType.SEASON) indexNumber else null,
        episodeNumber = if (isEpisode) indexNumber else null,
        year = productionYear,
        summary = overview,
        // 剧集卡片用剧的竖版海报，和 Plex 一致；剧集本身的 Primary 是截图
        poster = if (isEpisode) seriesPrimary ?: primary else primary ?: seriesPrimary,
        thumb = if (isEpisode) primary ?: landscape ?: backdrop else landscape ?: backdrop,
        backdrop = backdrop,
        durationMs = runTimeTicks?.ticksToMs(),
        viewOffsetMs = data?.playbackPositionTicks?.takeIf { it > 0 }?.ticksToMs(),
        watched = data?.played == true,
        unwatchedCount = data?.unplayedItemCount.takeIf { type == MediaType.SHOW || type == MediaType.SEASON },
        childCount = (childCount ?: recursiveItemCount).takeIf { type == MediaType.COLLECTION || type == MediaType.PLAYLIST },
    )
}

internal fun BaseItem.toDetail(segments: List<MediaSegmentDto>, onDeck: MediaItem?): MediaDetail {
    val item = toItem()
    val runtimeMs = runTimeTicks?.ticksToMs()
    val realChapters = chapters.filter { it.markerType == null || it.markerType == "Chapter" }
    return MediaDetail(
        item = item,
        tagline = taglines.firstOrNull(),
        contentRating = officialRating,
        rating = communityRating,
        genres = genres,
        directors = people.filter { it.type == "Director" }.map { it.name },
        cast = people.filter { it.type == "Actor" }.map { it.name },
        people = people.filter { it.type == "Actor" }.map { p ->
            MediaItem(
                id = p.id,
                type = MediaType.PERSON,
                title = p.name,
                summary = p.role,
                poster = p.primaryImageTag?.let { imageRef(p.id, "Primary", it) },
            )
        },
        // 按演员查作品时不限媒体库，这里只要非空
        libraryId = parentId ?: id,
        studio = studios.firstOrNull()?.name,
        versions = mediaSources.mapIndexed { i, s -> s.toVersion(id, i) },
        onDeck = onDeck,
        markers = (segments.mapNotNull { it.toMarker() } + chapterMarkers(runtimeMs)).sortedBy { it.startMs },
        chapters = realChapters.mapIndexed { i, c ->
            val start = c.startPositionTicks.ticksToMs()
            val end = realChapters.getOrNull(i + 1)?.startPositionTicks?.ticksToMs() ?: runtimeMs ?: start
            Chapter(
                index = i,
                title = c.name?.takeIf(String::isNotBlank),
                startMs = start,
                endMs = end,
                thumb = c.imageTag?.let { imageRef(id, "Chapter", it, chapters.indexOf(c)) },
            )
        },
    )
}

private fun MediaSegmentDto.toMarker(): Marker? {
    val type = when (type) {
        "Intro" -> MarkerType.INTRO
        "Outro" -> MarkerType.CREDITS
        else -> return null
    }
    return Marker(type, startTicks.ticksToMs(), endTicks.ticksToMs())
}

/** Emby 把片头 / 片尾放在章节里：IntroStart → IntroEnd，CreditsStart 到片尾 */
private fun BaseItem.chapterMarkers(runtimeMs: Long?): List<Marker> {
    val introStart = chapters.firstOrNull { it.markerType == "IntroStart" }?.startPositionTicks?.ticksToMs()
    val introEnd = chapters.firstOrNull { it.markerType == "IntroEnd" }?.startPositionTicks?.ticksToMs()
    val credits = chapters.firstOrNull { it.markerType == "CreditsStart" }?.startPositionTicks?.ticksToMs()
    return listOfNotNull(
        if (introStart != null && introEnd != null && introEnd > introStart) Marker(MarkerType.INTRO, introStart, introEnd) else null,
        if (credits != null && runtimeMs != null && runtimeMs > credits) Marker(MarkerType.CREDITS, credits, runtimeMs) else null,
    )
}

internal fun MediaSourceDto.toVersion(itemId: String, index: Int): MediaVersion {
    val video = mediaStreams.firstOrNull { it.type == "Video" }
    val container = container?.substringBefore(',')?.lowercase()
    return MediaVersion(
        id = id,
        index = index,
        container = container,
        bitrateKbps = bitrate?.let { it / 1000 },
        video = video?.let {
            VideoStream(
                codec = it.codec.orEmpty(),
                width = it.width,
                height = it.height,
                frameRate = it.realFrameRate ?: it.averageFrameRate,
                bitDepth = it.bitDepth,
                profile = it.profile,
                hdr = it.hdrType(),
                doviProfile = it.dvProfile ?: it.extendedVideoSubType?.let(::embyDoviProfile),
                bitrateKbps = it.bitRate?.let { b -> b / 1000 },
                title = it.displayTitle ?: it.title,
                language = it.language,
            )
        },
        audio = mediaStreams.filter { it.type == "Audio" }.map {
            AudioStream(
                id = it.index.toString(),
                // 服务端的 Index 就是 ffprobe 的流序号，和容器里的一致
                index = it.index,
                codec = it.codec.orEmpty(),
                channels = it.channels,
                language = it.language,
                title = it.displayTitle ?: it.title ?: it.codec.orEmpty(),
                selected = it.index == defaultAudioStreamIndex,
                bitrateKbps = it.bitRate?.let { b -> b / 1000 },
                sampleRate = it.sampleRate,
            )
        },
        subtitles = mediaStreams.filter { it.type == "Subtitle" }.map {
            val codec = subtitleCodec(it.codec)
            SubtitleStream(
                id = it.index.toString(),
                index = it.index.takeIf { _ -> !it.isExternal },
                codec = codec,
                language = it.language,
                title = it.displayTitle ?: it.title ?: codec,
                forced = it.isForced,
                selected = it.index == defaultSubtitleStreamIndex,
                key = if (it.isExternal) "Videos/$itemId/$id/Subtitles/${it.index}/Stream.${subtitleFormat(codec)}" else null,
            )
        },
        parts = listOf(
            MediaPart(
                id = id,
                key = "Videos/$itemId/stream",
                container = container,
                sizeBytes = size,
                durationMs = runTimeTicks?.ticksToMs(),
                file = path,
            ),
        ),
    )
}

/** 换成和 Plex 一致的名字，DeviceProfile 按这些名字判断能否本机渲染 */
internal fun subtitleCodec(codec: String?): String = when (val c = codec?.lowercase().orEmpty()) {
    "subrip" -> "srt"
    "webvtt" -> "vtt"
    "pgssub" -> "pgs"
    "dvdsub" -> "dvd_subtitle"
    "dvbsub" -> "dvb_subtitle"
    else -> c
}

/** 外挂字幕下载时的格式后缀；图形字幕不会走外挂下载，原样给出 */
private fun subtitleFormat(codec: String): String = when (codec) {
    "ass", "ssa", "vtt", "srt" -> codec
    else -> codec.ifBlank { "srt" }
}

private fun MediaStreamDto.hdrType(): HdrType {
    val range = (videoRangeType ?: videoRange).orEmpty()
    return when {
        range.startsWith("DOVI", ignoreCase = true) || extendedVideoType.equals("DolbyVision", ignoreCase = true) -> HdrType.DOLBY_VISION
        range.equals("HDR10Plus", ignoreCase = true) || extendedVideoType.equals("Hdr10Plus", ignoreCase = true) -> HdrType.HDR10_PLUS
        range.equals("HLG", ignoreCase = true) || extendedVideoType.equals("HyperLogGamma", ignoreCase = true) -> HdrType.HLG
        range.startsWith("HDR", ignoreCase = true) -> HdrType.HDR10
        else -> HdrType.NONE
    }
}

/** Emby 的 DoviProfile81 / DoviProfile5 → 8 / 5 */
private fun embyDoviProfile(subType: String): Int? =
    Regex("DoviProfile(\\d)", RegexOption.IGNORE_CASE).find(subType)?.groupValues?.get(1)?.toIntOrNull()

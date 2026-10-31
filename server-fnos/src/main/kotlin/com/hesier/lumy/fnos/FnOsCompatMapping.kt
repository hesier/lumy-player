package com.hesier.lumy.fnos

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

/** Compatibility timestamps use 100-nanosecond ticks. */
internal const val TICKS_PER_MS = 10_000L

internal fun Long.ticksToMs(): Long = this / TICKS_PER_MS

/** Relative image references include a tag so changed images invalidate the cache. */
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
        // Episode Primary images are stills; use the series poster for portrait cards.
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
        // Actor queries span libraries; the UI only requires a nonempty library ID.
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

/** Compatibility chapters can encode intro and credits marker boundaries. */
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
        video = video?.toVideo(),
        audio = mediaStreams.filter { it.type == "Audio" }.map { it.toAudio(defaultAudioStreamIndex) },
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

private fun MediaStreamDto.toVideo(): VideoStream = VideoStream(
    codec = codec.orEmpty(),
    width = width,
    height = height,
    frameRate = realFrameRate ?: averageFrameRate,
    bitDepth = bitDepth,
    profile = profile,
    hdr = hdrType(),
    doviProfile = dvProfile ?: extendedVideoSubType?.let(::compatDoviProfile),
    bitrateKbps = bitRate?.let { it / 1000 },
    title = displayTitle ?: title,
    language = language,
)

private fun MediaStreamDto.toAudio(defaultIndex: Int?): AudioStream = AudioStream(
    id = index.toString(),
    // Compatibility indices correspond to ffprobe stream indices.
    index = index,
    codec = codec.orEmpty(),
    channels = channels,
    language = language,
    title = displayTitle ?: title ?: codec.orEmpty(),
    selected = index == defaultIndex,
    bitrateKbps = bitRate?.let { it / 1000 },
    sampleRate = sampleRate,
)

/** Normalize codec names used by DeviceProfile to determine native rendering support. */
internal fun subtitleCodec(codec: String?): String = when (val c = codec?.lowercase().orEmpty()) {
    "subrip" -> "srt"
    "webvtt" -> "vtt"
    "pgssub" -> "pgs"
    "dvdsub" -> "dvd_subtitle"
    "dvbsub" -> "dvb_subtitle"
    else -> c
}

/** File extensions for external subtitle downloads. */
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

/** Compatibility values DoviProfile81 / DoviProfile5 map to profiles 8 / 5. */
private fun compatDoviProfile(subType: String): Int? =
    Regex("DoviProfile(\\d)", RegexOption.IGNORE_CASE).find(subType)?.groupValues?.get(1)?.toIntOrNull()

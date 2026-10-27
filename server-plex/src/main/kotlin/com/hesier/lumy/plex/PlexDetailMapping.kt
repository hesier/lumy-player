package com.hesier.lumy.plex

import com.hesier.lumy.core.AudioStream
import com.hesier.lumy.core.Chapter
import com.hesier.lumy.core.HdrType
import com.hesier.lumy.core.Marker
import com.hesier.lumy.core.MarkerType
import com.hesier.lumy.core.MediaDetail
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.MediaPart
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.SubtitleStream
import com.hesier.lumy.core.VideoStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal fun PlexMetadata.toDetail(json: Json): MediaDetail = MediaDetail(
    item = toItem(),
    tagline = tagline,
    contentRating = contentRating,
    rating = audienceRating ?: rating,
    genres = genres.map { it.tag },
    directors = directors.map { it.tag },
    cast = roles.map { it.tag },
    people = roles.mapNotNull { r ->
        val id = r.id ?: return@mapNotNull null
        MediaItem(id = id.toString(), type = MediaType.PERSON, title = r.tag, summary = r.role, poster = r.thumb)
    },
    libraryId = librarySectionID?.toString(),
    studio = studio,
    versions = media.mapIndexedNotNull { i, m -> m.toVersion(i) },
    onDeck = onDeckItems(json).firstOrNull()?.toItem(),
    markers = markers.mapNotNull { m ->
        val type = when (m.type) {
            "intro" -> MarkerType.INTRO
            "credits" -> MarkerType.CREDITS
            else -> return@mapNotNull null
        }
        Marker(type, m.startTimeOffset, m.endTimeOffset)
    }.sortedBy { it.startMs },
    chapters = chapters.map { Chapter(it.index, it.tag?.takeIf(String::isNotBlank), it.startTimeOffset, it.endTimeOffset, it.thumb) }
        .sortedBy { it.startMs },
)

private fun PlexMetadata.onDeckItems(json: Json): List<PlexMetadata> = when (val m = onDeck?.metadata) {
    is JsonObject -> listOf(json.decodeFromJsonElement(PlexMetadata.serializer(), m))
    is JsonArray -> m.map { json.decodeFromJsonElement(PlexMetadata.serializer(), it) }
    else -> emptyList()
}

/** 多段文件（CD1/CD2）的音轨/字幕各段一致，取第一段的流信息 */
internal fun PlexMedia.toVersion(index: Int): MediaVersion? {
    val part = parts.firstOrNull() ?: return null
    val video = part.streams.firstOrNull { it.streamType == 1 }
    return MediaVersion(
        id = id.toString(),
        index = index,
        container = part.container ?: container,
        bitrateKbps = bitrate,
        video = video?.let {
            VideoStream(
                codec = it.codec,
                width = it.width,
                height = it.height,
                frameRate = it.frameRate,
                bitDepth = it.bitDepth,
                profile = it.profile,
                hdr = it.hdrType(),
                doviProfile = it.doviProfile?.takeIf { _ -> it.doviPresent },
                bitrateKbps = it.bitrate,
                title = it.extendedDisplayTitle ?: it.displayTitle,
                language = it.languageTag ?: it.languageCode,
            )
        },
        audio = part.streams.filter { it.streamType == 2 }.map {
            AudioStream(
                id = it.id.toString(),
                index = it.index,
                codec = it.codec,
                channels = it.channels,
                language = it.languageTag ?: it.languageCode,
                title = it.extendedDisplayTitle ?: it.displayTitle ?: it.codec,
                selected = it.selected,
                bitrateKbps = it.bitrate,
                sampleRate = it.samplingRate,
            )
        },
        subtitles = part.streams.filter { it.streamType == 3 }.map {
            SubtitleStream(
                id = it.id.toString(),
                index = it.index?.takeIf { _ -> it.key == null },
                codec = it.codec,
                language = it.languageTag ?: it.languageCode,
                title = it.extendedDisplayTitle ?: it.displayTitle ?: it.codec,
                forced = it.forced,
                selected = it.selected,
                key = it.key,
            )
        },
        parts = parts.map {
            MediaPart(
                id = it.id.toString(),
                key = it.key,
                container = it.container,
                sizeBytes = it.size,
                durationMs = it.duration,
                file = it.file,
            )
        },
    )
}

private fun PlexStream.hdrType(): HdrType = when {
    doviPresent -> HdrType.DOLBY_VISION
    colorTrc == "smpte2084" -> HdrType.HDR10
    colorTrc == "arib-std-b67" -> HdrType.HLG
    else -> HdrType.NONE
}

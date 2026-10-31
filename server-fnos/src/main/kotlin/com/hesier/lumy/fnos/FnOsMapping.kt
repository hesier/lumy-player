package com.hesier.lumy.fnos

import com.hesier.lumy.core.LibraryQuery
import com.hesier.lumy.core.MediaDetail
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.ResolutionFilter
import com.hesier.lumy.core.SortOrder
import com.hesier.lumy.core.WatchFilter

// fnOS supplies a placeholder item duration; the source has the measured duration.
internal fun BaseItem.fnOsItem(): BaseItem = copy(
    runTimeTicks = mediaSources.maxOfOrNull { it.runTimeTicks ?: 0 }
        ?.takeIf { it > SOURCE_PLACEHOLDER_TICKS },
)

internal fun BaseItem.fnOsDetail(): MediaDetail {
    val detail = fnOsItem().toDetail(emptyList(), null)
    return detail.copy(versions = mediaSources.mapIndexed { index, source -> source.fnOsVersion(id, index) })
}

private fun MediaSourceDto.fnOsVersion(itemId: String, versionIndex: Int): MediaVersion {
    val version = toVersion(itemId, versionIndex)
    val audio = mediaStreams.filter { it.type == "Audio" }
    val subtitles = mediaStreams.filter { it.type == "Subtitle" }
    // All stream indices can be zero. Let the player match native tracks by order.
    return version.copy(
        audio = version.audio.mapIndexed { index, stream ->
            stream.copy(
                id = "audio:$index", index = null, selected = audio[index].isDefault,
                title = stream.title.takeIf { it.isNotBlank() } ?: trackTitle(stream.language, stream.codec),
            )
        },
        subtitles = version.subtitles.mapIndexed { index, stream ->
            val codec = when (stream.codec) {
                "hdmv_pgs_subtitle" -> "pgs"
                else -> stream.codec
            }
            stream.copy(
                id = "subtitle:$index", index = null, codec = codec,
                title = stream.title.takeIf { it.isNotBlank() } ?: trackTitle(stream.language, codec),
                selected = subtitles[index].isDefault,
                key = if (stream.external) "Videos/$itemId/$id/Subtitles/$index/0/Stream.$codec" else null,
            )
        },
        parts = version.parts.map { it.copy(durationMs = it.durationMs?.takeIf { ms -> ms > 1_000 }) },
    )
}

private fun trackTitle(language: String?, codec: String): String =
    listOfNotNull(language?.takeIf { it.isNotBlank() }, codec.uppercase()).joinToString(" · ")

internal fun List<BaseItem>.fnOsQuery(query: LibraryQuery): List<BaseItem> {
    val filtered = filter { item ->
        val watched = item.userData?.played == true
        val watchMatches = when (query.watch) {
            WatchFilter.ALL -> true
            WatchFilter.WATCHED -> watched
            WatchFilter.UNWATCHED -> !watched
        }
        watchMatches && (query.genreId == null || query.genreId in item.genres) && item.matchesResolution(query.resolution)
    }
    return when (query.sort) {
        SortOrder.TITLE -> filtered.sortedBy { (it.sortName ?: it.name).lowercase() }
        SortOrder.ADDED -> filtered.sortedByDescending { it.dateCreated.orEmpty() }
        SortOrder.RELEASED -> filtered.sortedByDescending { it.premiereDate ?: it.productionYear?.toString().orEmpty() }
        SortOrder.RATING -> filtered.sortedByDescending { it.communityRating ?: 0.0 }
        SortOrder.LAST_VIEWED -> filtered.sortedByDescending { it.userData?.lastPlayedDate.orEmpty() }
    }
}

private fun BaseItem.matchesResolution(resolution: ResolutionFilter): Boolean {
    if (resolution == ResolutionFilter.ALL) return true
    return mediaSources.any { source ->
        source.mediaStreams.any stream@{ stream ->
            val width = stream.width ?: return@stream false
            stream.type == "Video" && when (resolution) {
                ResolutionFilter.UHD_4K -> width >= 3800
                ResolutionFilter.FHD_1080 -> width in 1900..3799
                ResolutionFilter.HD_720 -> width in 1260..1899
                ResolutionFilter.SD -> width < 1260
                ResolutionFilter.ALL -> true
            }
        }
    }
}

private const val SOURCE_PLACEHOLDER_TICKS = 10_000_000L

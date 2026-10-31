package com.hesier.lumy.ui.player

import com.hesier.lumy.core.SubtitleStream

/** A changed PGS selection takes effect only after rebuilding the media source. */
class PgsTrackSelection {
    @Volatile internal var snapshot: PgsSelection = PgsSelection.All
        private set

    internal fun configure(subtitle: SubtitleStream?, filterTracks: Boolean, secondary: SubtitleStream? = null) {
        val tracks = listOfNotNull(subtitle, secondary).filter { !it.external && it.codec.lowercase() in SupMediaSourceFactory.CODECS }
        snapshot = when {
            !filterTracks -> PgsSelection.All
            tracks.isEmpty() -> PgsSelection.Off
            tracks.any { (it.index ?: -1) < 0 } -> PgsSelection.All
            else -> PgsSelection.Track((tracks.first().index!! + 1).toString(), tracks.getOrNull(1)?.index?.let { (it + 1).toString() })
        }
    }
}

internal sealed interface PgsSelection {
    data object All : PgsSelection
    data object Off : PgsSelection
    data class Track(val id: String, val secondaryId: String? = null) : PgsSelection {
        fun renders(trackId: String?, knownIds: Set<String?>): Boolean {
            val selected = listOfNotNull(id, secondaryId)
            return selected.none { it in knownIds } || trackId in selected
        }
    }
}

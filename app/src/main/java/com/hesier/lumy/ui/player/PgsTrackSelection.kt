package com.hesier.lumy.ui.player

import com.hesier.lumy.core.SubtitleStream

/** A changed PGS selection takes effect only after rebuilding the media source. */
class PgsTrackSelection {
    @Volatile internal var snapshot: PgsSelection = PgsSelection.All
        private set

    internal fun configure(subtitle: SubtitleStream?, filterTracks: Boolean) {
        val index = subtitle?.index
        snapshot = when {
            !filterTracks -> PgsSelection.All
            subtitle == null || subtitle.external || subtitle.codec.lowercase() !in SupMediaSourceFactory.CODECS -> PgsSelection.Off
            index == null || index < 0 -> PgsSelection.All
            else -> PgsSelection.Track((index + 1).toString())
        }
    }
}

internal sealed interface PgsSelection {
    data object All : PgsSelection
    data object Off : PgsSelection
    data class Track(val id: String) : PgsSelection
}

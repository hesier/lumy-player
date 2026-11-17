package com.hesier.lumy.ui.player

import com.hesier.lumy.core.MediaItem

internal object PlaybackResume {
    // Matches the launcher's completion boundary; completed entries start over.
    fun position(item: MediaItem): Long {
        val position = item.viewOffsetMs ?: return 0
        if (item.watched || position <= MIN_POSITION_MS) return 0
        val duration = item.durationMs
        if (duration != null && duration > 0 &&
            (position >= duration * COMPLETION_RATIO || duration - position <= MIN_REMAINING_MS)) return 0
        return position
    }

    private const val MIN_POSITION_MS = 30_000L
    private const val MIN_REMAINING_MS = 30_000L
    private const val COMPLETION_RATIO = 0.90
}

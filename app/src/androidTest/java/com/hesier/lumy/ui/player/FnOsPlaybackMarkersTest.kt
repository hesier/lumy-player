package com.hesier.lumy.ui.player

import com.hesier.lumy.core.Marker
import com.hesier.lumy.core.MarkerType
import com.hesier.lumy.core.MediaDetail
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaType

/** Checks tail-relative markers without changing the immutable source detail on version switches. */
internal object FnOsPlaybackMarkersTest {
    fun runAll() {
        val intro = Marker(MarkerType.INTRO, 0, 46_000)
        val credits = Marker(MarkerType.CREDITS, 1_324_000, 1_364_000)
        val original = listOf(intro, credits)
        val detail = MediaDetail(MediaItem("episode", MediaType.EPISODE, "Episode", durationMs = 1_364_000), markers = original)
        check(listOf(intro, Marker(MarkerType.CREDITS, 1_344_000, 1_384_000)) == detail.fnOsPlaybackMarkers(1_384_000))
        check(original == detail.fnOsPlaybackMarkers(1_364_000))
        check(original == detail.markers)
        check(original == detail.fnOsPlaybackMarkers(0))
        check(listOf(intro) == detail.fnOsPlaybackMarkers(60_000)) { "Overlapping credits must not replace the intro" }
        check(listOf(intro) == detail.fnOsPlaybackMarkers(86_000)) { "Skip ranges must leave some content" }
        check(emptyList<Marker>() == detail.fnOsPlaybackMarkers(40_000)) { "Short versions must not skip the entire file" }
        val absolute = Marker(MarkerType.CREDITS, 1_300_000, 1_320_000)
        check(listOf(intro, absolute) == detail.copy(markers = listOf(intro, absolute)).fnOsPlaybackMarkers(1_384_000))
        val compat = Marker(MarkerType.CREDITS, 1_300_000, 1_364_000)
        check(listOf(Marker(MarkerType.CREDITS, 1_320_000, 1_384_000)) ==
            detail.copy(markers = listOf(compat)).fnOsPlaybackMarkers(1_384_000))
    }
}

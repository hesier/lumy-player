@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

class DualSubtitleTrackSelectorTest {
    fun runAll(context: Context) {
        val primary = group("primary", MimeTypes.APPLICATION_MEDIA3_CUES)
        val secondary = group("secondary", MimeTypes.APPLICATION_MEDIA3_CUES)
        val pgs = group("pgs", PgsSnapshotCodec.MIME_TYPE)
        val groups = TrackGroupArray(primary, secondary, pgs)
        val capabilities = arrayOf(renderer(MimeTypes.APPLICATION_MEDIA3_CUES), renderer(PgsSnapshotCodec.MIME_TYPE),
            renderer(PgsSnapshotCodec.MIME_TYPE), renderer(MimeTypes.APPLICATION_MEDIA3_CUES))
        val selector = DualSubtitleTrackSelector(context)
        selector.init(object : TrackSelector.InvalidationListener {
            override fun onTrackSelectionsInvalidated() = Unit
        }, DefaultBandwidthMeter.Builder(context).build())
        try {
            selector.parameters = selector.parameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(primary, 0)).build()
            selector.selectSecondary(secondary)
            var result = selector.selectTracks(capabilities, groups, MediaSource.MediaPeriodId("test"), Timeline.EMPTY)
            check(result.selections[0]?.trackGroup == primary)
            check(result.selections[3]?.trackGroup == secondary)
            check(result.tracks.groups.count { it.isSelected } == 2)
            selector.selectSecondary(pgs)
            result = selector.selectTracks(capabilities, groups, MediaSource.MediaPeriodId("test"), Timeline.EMPTY)
            check(result.selections[0]?.trackGroup == primary)
            check(result.selections[2]?.trackGroup == pgs)
            selector.parameters = selector.parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            result = selector.selectTracks(capabilities, groups, MediaSource.MediaPeriodId("test"), Timeline.EMPTY)
            check(result.selections[0] == null && result.selections[2]?.trackGroup == pgs)
            selector.selectSecondary(null)
            result = selector.selectTracks(capabilities, groups, MediaSource.MediaPeriodId("test"), Timeline.EMPTY)
            check(result.selections.all { it == null })
            selector.parameters = selector.parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
            selector.selectSecondary(primary)
            result = selector.selectTracks(capabilities, groups, MediaSource.MediaPeriodId("test"), Timeline.EMPTY)
            check(result.selections.count { it != null } == 1)
        } finally { selector.release() }
    }

    private fun group(id: String, mime: String) = TrackGroup(id, Format.Builder().setId(id).setSampleMimeType(mime).build())

    private fun renderer(mime: String): RendererCapabilities = object : RendererCapabilities {
        override fun getName() = "DualSubtitleTest"
        override fun getTrackType() = C.TRACK_TYPE_TEXT
        override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
        override fun supportsFormat(format: Format) = RendererCapabilities.create(
            if (format.sampleMimeType == mime) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE,
        )
    }
}

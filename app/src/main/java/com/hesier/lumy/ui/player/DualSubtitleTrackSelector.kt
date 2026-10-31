@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelectorResult
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.ExoPlayer
import io.github.peerless2012.ass.media.AssHandler

/** Keep normal AV selection, then attach a second subtitle stream to its own renderer. */
class DualSubtitleTrackSelector(context: Context) : TrackSelector() {
    private val primary = DefaultTrackSelector(context)
    @Volatile private var secondary: TrackGroup? = null
    @Volatile var secondaryAssEnabled = false
    var onCues: ((CueGroup) -> Unit)? = null
    val output = object : TextOutput {
        override fun onCues(cueGroup: CueGroup) { onCues?.invoke(cueGroup) }
    }

    fun attachAss(player: ExoPlayer, first: AssHandler, second: AssHandler) {
        first.init(player)
        second.init(player)
        player.removeListener(first)
        player.removeListener(second)
        player.addListener(object : Player.Listener by first {
            override fun onTracksChanged(tracks: Tracks) {
                first.onTracksChanged(Tracks(tracks.groups.filter { it.mediaTrackGroup != secondary }))
                second.onTracksChanged(Tracks(tracks.groups.filter {
                    it.type != C.TRACK_TYPE_TEXT || it.mediaTrackGroup == secondary
                }))
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                first.onMediaItemTransition(mediaItem, reason)
                second.onMediaItemTransition(mediaItem, reason)
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                first.onVideoSizeChanged(videoSize)
                second.onVideoSizeChanged(videoSize)
            }
            override fun onSurfaceSizeChanged(width: Int, height: Int) {
                first.onSurfaceSizeChanged(width, height)
                second.onSurfaceSizeChanged(width, height)
            }
        })
    }

    fun selectSecondary(group: TrackGroup?) {
        if (secondary == group) return
        secondary = group
        invalidate()
    }

    override fun init(listener: InvalidationListener, bandwidthMeter: BandwidthMeter) {
        super.init(listener, bandwidthMeter)
        primary.init(listener, bandwidthMeter)
    }

    override fun getParameters(): TrackSelectionParameters = primary.parameters
    override fun isSetParametersSupported() = true
    override fun setParameters(parameters: TrackSelectionParameters) = primary.setParameters(parameters)
    override fun setAudioAttributes(audioAttributes: AudioAttributes) = primary.setAudioAttributes(audioAttributes)
    override fun getRendererCapabilitiesListener() = primary.rendererCapabilitiesListener
    override fun onSelectionActivated(info: Any?) = primary.onSelectionActivated(info)
    override fun release() = primary.release()

    override fun selectTracks(
        rendererCapabilities: Array<RendererCapabilities>,
        trackGroups: TrackGroupArray,
        mediaPeriodId: MediaSource.MediaPeriodId,
        timeline: Timeline,
    ): TrackSelectorResult {
        val secondaryIndices = (rendererCapabilities.lastIndex - 1)..rendererCapabilities.lastIndex
        // Hide the extra renderer from default mapping so primary selection stays unchanged.
        val masked = rendererCapabilities.copyOf()
        secondaryIndices.forEach { index ->
            masked[index] = object : RendererCapabilities by rendererCapabilities[index] {
                override fun supportsFormat(format: Format) = RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
            }
        }
        val result = primary.selectTracks(masked, trackGroups, mediaPeriodId, timeline)
        val group = secondary?.takeIf { trackGroups.indexOf(it) != C.INDEX_UNSET }
            ?.takeUnless { candidate -> result.selections.any { it?.trackGroup == candidate } }
            ?: return result
        val index = secondaryIndices.firstOrNull {
            RendererCapabilities.getFormatSupport(rendererCapabilities[it].supportsFormat(group.getFormat(0))) == C.FORMAT_HANDLED
        } ?: return result
        val selections = result.selections.copyOf()
        val configurations = result.rendererConfigurations.copyOf()
        selections[index] = FixedTrackSelection(group, 0)
        configurations[index] = RendererConfiguration.DEFAULT
        val tracks = Tracks(result.tracks.groups.map { current ->
            if (current.mediaTrackGroup != group) current else Tracks.Group(
                group, false, IntArray(group.length) { current.getTrackSupport(it) }, BooleanArray(group.length) { it == 0 },
            )
        })
        return TrackSelectorResult(configurations, selections, tracks, result.info)
    }
}

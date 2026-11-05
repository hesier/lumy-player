@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.hesier.lumy.ui.player.PlaybackStartupTiming.Measurement
import com.hesier.lumy.ui.player.PlaybackStartupTiming.Stage

/** Resolve the event's timeline, never the player's newer current media item. */
internal class PlaybackStartupTimingListener(private val timing: PlaybackStartupTiming) : AnalyticsListener {
    private val window = Timeline.Window()

    internal fun attempt(event: AnalyticsListener.EventTime): PlaybackStartupTiming.Attempt? {
        if (event.windowIndex !in 0 until event.timeline.windowCount) return null
        return event.timeline.getWindow(event.windowIndex, window).mediaItem.localConfiguration?.tag
            as? PlaybackStartupTiming.Attempt
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        if (state == Player.STATE_READY) timing.mark(attempt(eventTime), Stage.READY)
    }

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
        initializedTimestampMs: Long, initializationDurationMs: Long) {
        timing.renderer(attempt(eventTime), Stage.VIDEO_DECODER, initializedTimestampMs, initializationDurationMs)
    }

    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
        initializedTimestampMs: Long, initializationDurationMs: Long) {
        timing.renderer(attempt(eventTime), Stage.AUDIO_DECODER, initializedTimestampMs, initializationDurationMs)
    }

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        timing.renderer(attempt(eventTime), Stage.FIRST_FRAME, renderTimeMs)
    }

    override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData) {
        if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA || mediaLoadData.trackType == C.TRACK_TYPE_TEXT) return
        val scheme = loadEventInfo.uri.scheme
        if ((scheme == "http" || scheme == "https") && loadEventInfo.bytesLoaded > 0) {
            // This includes open/read/extractor work and may complete after the first video frame.
            timing.mark(attempt(eventTime), Stage.HTTP_LOAD_COMPLETE,
                Measurement(loadEventInfo.loadDurationMs, loadEventInfo.bytesLoaded))
        }
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        if (timing.isActive(attempt(eventTime))) timing.end(PlaybackStartupTiming.End.ERROR)
    }
}

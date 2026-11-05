@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import com.hesier.lumy.ui.player.PlaybackStartupTiming.Stage

/** Deterministic offline fixture: delayed callbacks, attribution, cancellation and safe logging. */
class PlaybackStartupTimingTest(private val instrumentation: Instrumentation) {
    fun runAll(): String {
        instrumentation.runOnMainSync {
            readyIsNotFirstFrame()
            firstFrameCanPrecedeReady()
            replacedAndCancelledEventsAreIgnored()
            lateRendererTimestampIsIgnored()
            cancelledTransferIsIgnored()
            interleavedSourcesKeepIndependentCounts()
        }
        return "Startup timing: READY/first frame separation, event timeline attribution, late renderer/transfer cancellation and anonymous logs passed"
    }

    private fun readyIsNotFirstFrame() {
        var now = 100L
        val lines = mutableListOf<String>()
        val timing = PlaybackStartupTiming({ now }, lines::add)
        val attempt = timing.begin(withDetail = true)
        now = 120
        timing.mark(attempt, Stage.DETAIL_END)
        check(lines.last().contains("durationMs=20"))
        now = 125
        timing.mark(attempt, Stage.PREPARE)
        val listener = PlaybackStartupTimingListener(timing)
        now = 140
        listener.onPlaybackStateChanged(event(attempt, now), Player.STATE_READY)
        check(lines.any { "stage=READY" in it } && lines.none { "end=FIRST_FRAME" in it })
        now = 160
        listener.onRenderedFirstFrame(event(attempt, now), Any(), 150)
        check(lines.single { "stage=FIRST_FRAME" in it }.contains("eventElapsedMs=50"))
        check(lines.count { "end=FIRST_FRAME" in it } == 1)
        listener.onRenderedFirstFrame(event(attempt, now), Any(), 150)
        check(lines.count { "stage=FIRST_FRAME" in it } == 1)
    }

    private fun replacedAndCancelledEventsAreIgnored() {
        var now = 100L
        val lines = mutableListOf<String>()
        val timing = PlaybackStartupTiming({ now }, lines::add)
        val old = timing.begin()
        timing.mark(old, Stage.PREPARE)
        now = 200
        val current = timing.begin()
        timing.mark(current, Stage.PREPARE)
        val listener = PlaybackStartupTimingListener(timing)
        val count = lines.size
        // An event can refer to the old timeline while currentTimeline already contains the new item.
        listener.onPlaybackStateChanged(event(old, now, current), Player.STATE_READY)
        listener.onRenderedFirstFrame(event(old, now, current), Any(), now)
        check(lines.size == count)
        check(listener.attempt(event(null, now)) == null)
        check(listener.attempt(AnalyticsListener.EventTime(now, Timeline.EMPTY, 0, null, 0,
            Timeline.EMPTY, 0, null, 0, 0)) == null)
        timing.end(PlaybackStartupTiming.End.CANCELLED)
        val cancelledCount = lines.size
        listener.onPlaybackStateChanged(event(current, now), Player.STATE_READY)
        listener.onRenderedFirstFrame(event(current, now), Any(), now)
        check(lines.size == cancelledCount)
    }

    private fun firstFrameCanPrecedeReady() {
        var now = 100L
        val lines = mutableListOf<String>()
        val timing = PlaybackStartupTiming({ now }, lines::add)
        val attempt = timing.begin()
        timing.mark(attempt, Stage.PREPARE)
        val listener = PlaybackStartupTimingListener(timing)
        now = 130
        listener.onRenderedFirstFrame(event(attempt, now), Any(), 125)
        check(lines.count { "end=FIRST_FRAME" in it } == 1)
        now = 150
        listener.onPlaybackStateChanged(event(attempt, now), Player.STATE_READY)
        listener.onVideoDecoderInitialized(event(attempt, now), "fixture", 120, 10)
        listener.onAudioDecoderInitialized(event(attempt, now), "fixture", 120, 10)
        check(lines.count { "stage=READY" in it } == 1)
        check(lines.count { "stage=VIDEO_DECODER" in it } == 1)
        check(lines.count { "stage=AUDIO_DECODER" in it } == 1)
        listener.onPlaybackStateChanged(event(attempt, now), Player.STATE_READY)
        listener.onVideoDecoderInitialized(event(attempt, now), "fixture", 120, 10)
        check(lines.count { "stage=READY" in it } == 1)
        check(lines.count { "stage=VIDEO_DECODER" in it } == 1)
        check(lines.count { "end=FIRST_FRAME" in it } == 1)
        timing.end(PlaybackStartupTiming.End.CANCELLED)
        val count = lines.size
        listener.onPlaybackStateChanged(event(attempt, now), Player.STATE_READY)
        check(lines.size == count)
    }

    private fun lateRendererTimestampIsIgnored() {
        var now = 100L
        val lines = mutableListOf<String>()
        val timing = PlaybackStartupTiming({ now }, lines::add)
        val attempt = timing.begin()
        now = 200
        timing.mark(attempt, Stage.PREPARE)
        val listener = PlaybackStartupTimingListener(timing)
        val count = lines.size
        listener.onRenderedFirstFrame(event(attempt, now), Any(), 150)
        listener.onVideoDecoderInitialized(event(attempt, now), "fixture", 150, 25)
        check(lines.size == count)
        now = 250
        listener.onVideoDecoderInitialized(event(attempt, now), "fixture", 225, 25)
        check(lines.last().contains("stage=VIDEO_DECODER") && lines.last().contains("durationMs=25"))
    }

    private fun cancelledTransferIsIgnored() {
        val lines = mutableListOf<String>()
        val timing = PlaybackStartupTiming(output = lines::add)
        val listener = PlaybackStartupTimingTransfer(timing)
        val source = ByteArrayDataSource(byteArrayOf(1))
        val attempt = timing.begin()
        val spec = DataSpec.Builder().setUri("https://fixture.invalid/private-title?token=secret")
            .setKey(attempt.key).build()
        listener.onTransferInitializing(source, spec, true)
        timing.end(PlaybackStartupTiming.End.CANCELLED)
        val count = lines.size
        listener.onBytesTransferred(source, spec, true, 42)
        listener.onTransferEnd(source, spec, true)
        check(lines.size == count)
        val current = timing.begin()
        val unkeyed = DataSpec(Uri.parse("https://fixture.invalid/private-title?token=secret"))
        listener.onTransferInitializing(source, unkeyed, true)
        listener.onBytesTransferred(source, unkeyed, true, 42)
        check(lines.none { "stage=HTTP_FIRST_READ" in it })
        val keyed = unkeyed.buildUpon().setKey(current.key).build()
        listener.onTransferInitializing(source, keyed, true)
        listener.onBytesTransferred(source, keyed, true, 42)
        listener.onBytesTransferred(source, keyed, true, 21)
        listener.onTransferEnd(source, keyed, true)
        check(lines.count { "stage=HTTP_FIRST_READ" in it } == 1)
        check(lines.single { "stage=HTTP_TRANSFER_END" in it }.contains("bytes=63"))
        check(lines.none { "private-title" in it || "secret" in it || "https://" in it })
    }

    private fun interleavedSourcesKeepIndependentCounts() {
        val lines = mutableListOf<String>()
        val timing = PlaybackStartupTiming(output = lines::add)
        val listener = PlaybackStartupTimingTransfer(timing)
        val attempt = timing.begin()
        val spec = DataSpec.Builder().setUri("https://fixture.invalid").setKey(attempt.key).build()
        val first = ByteArrayDataSource(byteArrayOf(1))
        val second = ByteArrayDataSource(byteArrayOf(2))
        listener.onTransferInitializing(first, spec, true)
        listener.onTransferInitializing(second, spec, true)
        listener.onBytesTransferred(first, spec, true, 3)
        listener.onBytesTransferred(second, spec, true, 5)
        listener.onBytesTransferred(first, spec, true, 4)
        listener.onTransferEnd(first, spec, true)
        listener.onBytesTransferred(second, spec, true, 6)
        listener.onTransferEnd(second, spec, true)
        val closed = lines.filter { "phase=transfer_end" in it }
        check(closed.size == 2)
        check(closed.single { "http=1 " in it }.contains("deliveredBytes=7"))
        check(closed.single { "http=2 " in it }.contains("deliveredBytes=11"))
    }

    private fun event(attempt: PlaybackStartupTiming.Attempt?, now: Long,
        current: PlaybackStartupTiming.Attempt? = attempt): AnalyticsListener.EventTime {
        fun timeline(tag: PlaybackStartupTiming.Attempt?): Timeline = SinglePeriodTimeline(
            1_000_000, true, false, false, null,
            MediaItem.Builder().setUri("file:///startup-fixture").setTag(tag).build(),
        )
        return AnalyticsListener.EventTime(now, timeline(attempt), 0, null, 0,
            timeline(current), 0, null, 0, 0)
    }
}

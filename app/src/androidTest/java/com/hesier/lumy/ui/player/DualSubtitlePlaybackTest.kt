@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.CueGroup
import okhttp3.OkHttpClient
import java.io.File

/** Exercise the real extractor, selector, native ASS handlers and both output channels. */
class DualSubtitlePlaybackTest(private val instrumentation: Instrumentation) {
    private lateinit var bundle: PlayerBundle
    private var primary = CueGroup(emptyList(), 0)
    private var secondary = CueGroup(emptyList(), 0)
    private val offsets = SyncOffsets()

    fun runAll() {
        val file = File(instrumentation.targetContext.cacheDir, "dual-subtitles-test.mkv")
        instrumentation.context.assets.open("dual-subtitles/fixture.mkv").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        main {
            bundle = PlayerFactory.create(instrumentation.targetContext, OkHttpClient(), emptyMap(), offsets)
            bundle.ass.onSurfaceSizeChanged(320, 180)
            bundle.secondaryAss.onSurfaceSizeChanged(320, 180)
            bundle.player.addListener(object : Player.Listener {
                override fun onCues(cueGroup: CueGroup) { primary = cueGroup }
            })
            bundle.subtitles.onCues = { secondary = it }
            bundle.player.trackSelectionParameters = bundle.player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
            bundle.player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
            bundle.player.prepare()
        }
        try {
            await("six subtitle tracks") { textGroups().size == 6 }
            main {
                check(!bundle.secondaryAss.hasTracks())
                bundle.subtitles.secondaryAssEnabled = true
                bundle.player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
                bundle.player.prepare()
            }
            await("enable secondary ASS parsing") { textGroups().size == 6 && bundle.secondaryAss.hasTracks() }
            checkText()
            checkAss()
            checkPgs()
            main { bundle.subtitles.selectSecondary(null) }
            await("secondary off") { secondary.cues.isEmpty() }
        } finally {
            main { bundle.player.release(); bundle.ass.release(); bundle.secondaryAss.release() }
            file.delete()
        }
    }

    private fun checkText() {
        select(0, 1)
        await("SRT + SRT") { text(primary) == "TEXT 1" && text(secondary) == "TEXT 2" }
        main { bundle.player.setPlaybackSpeed(1.5f); bundle.player.play() }
        await("dual subtitles at 1.5x") {
            bundle.player.currentPosition >= 2_500 && text(primary) == "TEXT 1" && text(secondary) == "TEXT 2"
        }
        main { bundle.player.pause() }
        main { bundle.player.seekTo(12_000) }
        await("paused forward seek") { text(primary) == "LATER 1" && text(secondary) == "LATER 2" }
        main { bundle.player.seekTo(2_000) }
        await("paused backward seek") { text(primary) == "TEXT 1" && text(secondary) == "TEXT 2" }
        main { offsets.subtitleDelayMs = 2_000; bundle.player.seekTo(10_000) }
        await("shared subtitle delay after seek") { text(primary) == "TEXT 1" && text(secondary) == "TEXT 2" }
        main { offsets.subtitleDelayMs = 0; bundle.player.seekTo(12_000) }
        await("restore subtitle delay") { text(primary) == "LATER 1" && text(secondary) == "LATER 2" }
    }

    private fun checkAss() {
        select(2, 3)
        await("ASS + ASS native tracks") {
            assText(bundle.ass).contains("ASS 1") && assText(bundle.secondaryAss).contains("ASS 2")
        }
        main { check(hasAssFrame(bundle.ass) && hasAssFrame(bundle.secondaryAss)) }
        select(0, 3)
        await("SRT + ASS") { text(primary) == "TEXT 1" && !hasAss(bundle.ass) && hasAss(bundle.secondaryAss) }
        select(2, 1)
        await("ASS + SRT") { hasAss(bundle.ass) && text(secondary) == "TEXT 2" && !hasAss(bundle.secondaryAss) }
    }

    private fun checkPgs() {
        select(0, 4)
        await("SRT + PGS") { text(primary) == "TEXT 1" && hasBitmap(secondary) }
        select(4, 1)
        await("PGS + SRT") { hasBitmap(primary) && text(secondary) == "TEXT 2" }
        select(4, 5)
        await("PGS + PGS") { hasBitmap(primary) && hasBitmap(secondary) && primary.cues[0].line != secondary.cues[0].line }
        select(2, 5)
        await("ASS + PGS") { hasAss(bundle.ass) && hasBitmap(secondary) }
        select(4, 3)
        await("PGS + ASS") { hasBitmap(primary) && hasAss(bundle.secondaryAss) }
    }

    private fun select(first: Int, second: Int) = main {
        val groups = textGroups()
        bundle.player.trackSelectionParameters = bundle.player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(groups[first], 0)).build()
        bundle.subtitles.selectSecondary(groups[second])
        bundle.player.seekTo(2_000)
    }

    // ass-media hides its native model dependency from consumers' compile classpath.
    private fun track(handler: io.github.peerless2012.ass.media.AssHandler): Any? =
        handler.javaClass.getMethod("getTrack").invoke(handler)

    private fun hasAss(handler: io.github.peerless2012.ass.media.AssHandler) = track(handler) != null

    private fun hasAssFrame(handler: io.github.peerless2012.ass.media.AssHandler): Boolean {
        val render = handler.javaClass.getMethod("getRender").invoke(handler) ?: return false
        val type = Class.forName("io.github.peerless2012.ass.AssTexType")
        val bitmap = type.enumConstants!!.first { it.toString() == "BITMAP_RGBA" }
        val frame = render.javaClass.getMethod("renderFrame", Long::class.javaPrimitiveType, type)
            .invoke(render, 2_000L, bitmap) ?: return false
        return (frame.javaClass.getMethod("getImages").invoke(frame) as Array<*>).isNotEmpty()
    }

    private fun assText(handler: io.github.peerless2012.ass.media.AssHandler): String {
        val track = track(handler) ?: return ""
        val events = track.javaClass.getMethod("getEvents").invoke(track) as? Array<*> ?: return ""
        return events.filterNotNull().joinToString { it.javaClass.getMethod("getText").invoke(it).toString() }
    }

    private fun textGroups(): List<TrackGroup> = bundle.player.currentTracks.groups
        .filter { it.type == C.TRACK_TYPE_TEXT }.map { it.mediaTrackGroup }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var passed = false
            main { bundle.player.playerError?.let { throw it }; passed = condition() }
            if (passed) return
            Thread.sleep(20)
        }
        error("Timed out: $label")
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun text(group: CueGroup) = group.cues.mapNotNull { it.text }.joinToString("\n")
    private fun hasBitmap(group: CueGroup) = group.cues.any { it.bitmap != null }
}

package com.hesier.lumy.ui.player

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import androidx.media3.decoder.ffmpeg.TrueHdDecoderTest

/** Runs playback parser and decoder checks using the platform instrumentation APIs. */
class PgsInstrumentation : Instrumentation() {
    private var cardRefreshOnly = false
    private var benchmark = false
    private var dualSubtitlesOnly = false
    private var trackMemoryOnly = false
    private var seekSettingsOnly = false
    private var holdSpeedOnly = false
    private var playerControlsOnly = false
    private var rmFixture: String? = null
    private var softwareFixture: String? = null
    private var rmAudioMime = "audio/cook"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        cardRefreshOnly = arguments?.getString("card_refresh_only") == "true"
        benchmark = arguments?.getString("benchmark_sup") == "true"
        dualSubtitlesOnly = arguments?.getString("dual_subtitles_only") == "true"
        trackMemoryOnly = arguments?.getString("track_memory_only") == "true"
        playerControlsOnly = arguments?.getString("player_controls_only") == "true"
        holdSpeedOnly = arguments?.getString("hold_speed_only") == "true"
        seekSettingsOnly = arguments?.getString("seek_settings_only") == "true"
        rmFixture = arguments?.getString("rm_fixture")
        softwareFixture = arguments?.getString("software_fixture")
        rmAudioMime = arguments?.getString("rm_audio_mime") ?: "audio/cook"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            if (playerControlsOnly) {
                result.putString("stream", PlayerControlsPlaybackTest(this).runAll() + "\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (holdSpeedOnly) {
                HoldSpeedTest().runAll(targetContext)
                result.putString("stream", "Hold speed tap, hold, release, repeat, cancellation and settings checks passed\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (seekSettingsOnly) {
                runOnMainSync { SeekSettingsTest().runAll(targetContext) }
                result.putString("stream", "Independent seek intervals, persistence, defaults and player increment checks passed\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (trackMemoryOnly) {
                com.hesier.lumy.data.TrackMemoryTest(targetContext).runAll()
                result.putString("stream", "Track memory migration, independent switches, writes, clearing and selection checks passed\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (dualSubtitlesOnly) {
                runOnMainSync { DualSubtitleTrackSelectorTest().runAll(targetContext) }
                runOnMainSync { SecondarySubtitleStyleTest().runAll(targetContext) }
                PgsSnapshotTrackOutputTest().runAll()
                DualSubtitlePlaybackTest(this).runAll()
                result.putString("stream", "Dual subtitle selection, SRT/ASS/PGS playback, seek and delay checks passed\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            runOnMainSync {
                com.hesier.lumy.ui.CardRefreshTest().apply {
                    runAll()
                    checkPlaybackPosition(targetContext)
                }
            }
            if (cardRefreshOnly) {
                result.putString("stream", "Card and hub refresh checks passed\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            MultiObjectPgsParserTest().runAll()
            PgsSnapshotCodecTest().runAll()
            PgsSnapshotTrackOutputTest().runAll()
            PgsSnapshotDecoderTest().runAll()
            PgsSnapshotRendererTest().runAll()
            PgsParserFactoryTest().runAll()
            runOnMainSync { DualSubtitleTrackSelectorTest().runAll(targetContext) }
            SupExtractorTest().runAll()
            MatroskaIndexTest().runAll()
            TrueHdDecoderTest().runAll(context)
            DolbyVisionCompatibilityTest().runAll(targetContext)
            com.hesier.lumy.ui.player.legacy.SoftwareVideoDecoderTest().runAll()
            com.hesier.lumy.ui.player.legacy.LegacyRmExtractorTest().runAll()
            rmFixture?.let { path ->
                com.hesier.lumy.ui.player.legacy.LegacyRmExtractorTest().runFixture(
                    androidx.media3.datasource.DefaultDataSource.Factory(targetContext),
                    android.net.Uri.fromFile(java.io.File(path)), rmAudioMime,
                )
            }
            softwareFixture?.let {
                com.hesier.lumy.ui.player.legacy.SoftwareRv40FixtureTest().runFixture(it)
            }
            val performance = if (benchmark) PgsPerformanceProbe().run(targetContext.filesDir) else ""
            result.putString("stream", "Card refresh, PGS, track filtering, SUP, Matroska index, TrueHD, Dolby Vision, software video and RM transport checks passed\n$performance")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", Log.getStackTraceString(error))
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}

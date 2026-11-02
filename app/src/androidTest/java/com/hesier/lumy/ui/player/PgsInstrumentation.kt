package com.hesier.lumy.ui.player

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import androidx.media3.decoder.ffmpeg.TrueHdDecoderTest

/** Runs playback parser and decoder checks using the platform instrumentation APIs. */
class PgsInstrumentation : Instrumentation() {
    private var danmakuPluginArguments: Bundle? = null
    private var danmakuFlow = false
    private var danmakuFlowLive = false
    private var danmakuControls = false
    private var danmakuSearchKeyword: String? = null
    private var danmakuBenchmarkArguments: Bundle? = null
    private var danmakuSourcesUiOnly = false
    private var danmakuLiveUrl: String? = null
    private var danmakuLiveAnime: String? = null
    private var danmakuApiOnly = false
    private var danmakuOverlayOnly = false
    private var danmakuSettingsOnly = false
    private var danmakuOnly = false
    private var cardRefreshOnly = false
    private var benchmark = false
    private var dualSubtitlesOnly = false
    private var trackMemoryOnly = false
    private var backupOnly = false
    private var playbackNoticeOnly = false
    private var bandwidthSettingsOnly = false
    private var seekSettingsOnly = false
    private var holdSpeedOnly = false
    private var playerControlsOnly = false
    private var rmFixture: String? = null
    private var softwareFixture: String? = null
    private var rmAudioMime = "audio/cook"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        if (arguments?.containsKey("danmaku_plugin_item") == true) danmakuPluginArguments = Bundle(arguments)
        danmakuFlow = arguments?.getString("danmaku_flow_only") == "true"
        danmakuFlowLive = arguments?.getString("danmaku_flow_live") == "true"
        danmakuControls = arguments?.getString("danmaku_controls_only") == "true"
        danmakuSearchKeyword = arguments?.getString("danmaku_search_keyword")
        if (arguments?.containsKey("danmaku_benchmark_item") == true) danmakuBenchmarkArguments = Bundle(arguments)
        danmakuSourcesUiOnly = arguments?.getString("danmaku_sources_ui_only") == "true"
        danmakuLiveUrl = arguments?.getString("danmaku_live_url")
        danmakuLiveAnime = arguments?.getString("danmaku_live_anime")
        danmakuApiOnly = arguments?.getString("danmaku_api_only") == "true"
        danmakuOverlayOnly = arguments?.getString("danmaku_overlay_only") == "true"
        danmakuSettingsOnly = arguments?.getString("danmaku_settings_only") == "true"
        danmakuOnly = arguments?.getString("danmaku_only") == "true"
        cardRefreshOnly = arguments?.getString("card_refresh_only") == "true"
        benchmark = arguments?.getString("benchmark_sup") == "true"
        dualSubtitlesOnly = arguments?.getString("dual_subtitles_only") == "true"
        trackMemoryOnly = arguments?.getString("track_memory_only") == "true"
        backupOnly = arguments?.getString("backup_only") == "true"
        playerControlsOnly = arguments?.getString("player_controls_only") == "true"
        holdSpeedOnly = arguments?.getString("hold_speed_only") == "true"
        playbackNoticeOnly = arguments?.getString("playback_notice_only") == "true"
        bandwidthSettingsOnly = arguments?.getString("bandwidth_settings_only") == "true"
        seekSettingsOnly = arguments?.getString("seek_settings_only") == "true"
        rmFixture = arguments?.getString("rm_fixture")
        softwareFixture = arguments?.getString("software_fixture")
        rmAudioMime = arguments?.getString("rm_audio_mime") ?: "audio/cook"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            if (danmakuPluginArguments != null) {
                result.putString("stream", DanmakuPluginLiveTest(this).runAll(danmakuPluginArguments!!) + "\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (danmakuControls) {
                result.putString("stream", DanmakuControlBarTest(this).runAll() + "\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (danmakuFlow || danmakuFlowLive) {
                result.putString("stream", DanmakuPlayerFlowTest(this).runAll(danmakuFlowLive) + "\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (danmakuSearchKeyword != null) {
                result.putString("stream", DanmakuLiveSearchTest(this).runAll(danmakuSearchKeyword!!) + "\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (danmakuBenchmarkArguments != null) {
                result.putString("stream", DanmakuDeviceBenchmark(this).runAll(danmakuBenchmarkArguments!!) + "\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (danmakuSourcesUiOnly) {
                com.hesier.lumy.ui.settings.DanmakuSourcesUiTest(this).runAll()
                result.putString("stream", "Danmaku source D-pad settings checks passed\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (danmakuLiveUrl != null && danmakuLiveAnime != null) {
                result.putString("stream", DanmakuLiveOverlayTest(this).runAll(danmakuLiveUrl!!, danmakuLiveAnime!!) + "\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (danmakuApiOnly) {
                com.hesier.lumy.data.DanmakuMatcherTest.runAll()
                result.putString("stream", com.hesier.lumy.data.DanmakuRepositoryTest(targetContext).runAll() + "\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (danmakuOverlayOnly) {
                result.putString("stream", DanmakuOverlayTest(this).runAll() + "\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (danmakuSettingsOnly) {
                com.hesier.lumy.data.DanmakuPreferencesTest().runAll(targetContext)
                com.hesier.lumy.data.DanmakuSourcesPreferencesTest().runAll(targetContext)
                com.hesier.lumy.ui.settings.DanmakuSettingsUiTest(this).runAll()
                com.hesier.lumy.ui.settings.DanmakuSourcesUiTest(this).runAll()
                result.putString("stream", "Danmaku defaults, persistence, backup validation, reset and D-pad settings checks passed\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (danmakuOnly) {
                result.putString("stream", DanmakuProbeTest(this).runAll() + "\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (backupOnly) {
                com.hesier.lumy.data.backup.BackupStoreTest(targetContext).runAll()
                com.hesier.lumy.ui.backup.BackupDocumentsTest(this).runAll()
                com.hesier.lumy.ui.backup.BackupUiTest(this).runAll()
                result.putString("stream", "Backup schema, scopes, credentials, rollback, device encryption and D-pad checks passed\n")
                finish(Activity.RESULT_OK, result)
                return
            }
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
            if (playbackNoticeOnly) {
                PlaybackNoticeTest(this).runAll()
                result.putString("stream", "Transcode reason accuracy, all notice texts, deduplication, timeout and remote focus checks passed\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (bandwidthSettingsOnly) {
                runOnMainSync { BandwidthSettingsTest().runAll(targetContext) }
                result.putString("stream", "Per-server bandwidth, transcoding migration and playback planning checks passed\n")
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

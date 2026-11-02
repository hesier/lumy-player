@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hesier.lumy.ui.player

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import com.hesier.lumy.LumyApp
import com.hesier.lumy.core.PlaybackMode
import com.hesier.lumy.core.PlaybackRequest
import com.hesier.lumy.data.DanmakuDensity
import com.hesier.lumy.data.DanmakuServices
import com.hesier.lumy.data.DanmakuSettings
import com.hesier.lumy.data.DanmakuStroke
import com.hesier.lumy.databinding.ActivityPlayerBinding
import com.hesier.lumy.net.danmaku.DanmakuApi
import com.hesier.lumy.net.danmaku.DanmakuComment
import com.hesier.lumy.net.danmaku.DanmakuEndpoint
import com.hesier.lumy.ui.settings.SettingsActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

/** Direct Play benchmark bypasses watch-progress writes and uses isolated preferences. */
class DanmakuDeviceBenchmark(private val instrumentation: Instrumentation) {
    private lateinit var config: DanmakuDeviceBenchmarkConfig
    private lateinit var report: DanmakuDeviceBenchmarkReport
    private lateinit var activity: Activity
    private lateinit var binding: ActivityPlayerBinding
    private lateinit var bundle: PlayerBundle
    private lateinit var stats: DanmakuDeviceBenchmarkStats
    private var overlay: DanmakuOverlay? = null
    private var audioOverride: TrackSelectionOverride? = null
    private val player get() = bundle.player
    private val preferences get() = instrumentation.targetContext.getSharedPreferences("danmaku-device-benchmark", Context.MODE_PRIVATE)
    private val summaries = mutableListOf<String>()
    fun runAll(arguments: Bundle): String {
        require(Build.VERSION.SDK_INT >= 24) { "Benchmark requires API 24+ window frame metrics" }
        config = DanmakuDeviceBenchmarkConfig(arguments)
        report = DanmakuDeviceBenchmarkReport(instrumentation)
        val started = SystemClock.elapsedRealtime()
        val comments = loadSafely(::loadComments)
        report.write(JSONObject().put("event", "comments_loaded").put("loadMs", SystemClock.elapsedRealtime() - started)
            .put("comments", comments.size).put("commentTextUtf8Bytes", comments.sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() }))
        val playback = loadSafely(::loadPlayback)
        check(preferences.edit().clear().commit())
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            main { setup(playback.first, playback.second) }
            config.profiles.forEachIndexed { index, profile -> runProfile(index, profile, comments) }
        } catch (failure: Throwable) {
            report.write(JSONObject().put("event", "failed").put("type", failure.javaClass.simpleName))
            throw failure
        } finally {
            main { release() }
            check(preferences.edit().clear().commit())
        }
        return summaries.joinToString("\n")
    }
    private fun <T> loadSafely(load: () -> T): T = try { load() } catch (failure: Exception) {
        // Media client exceptions may contain private URLs; discard their message and cause.
        error("Benchmark loading failed: ${failure.javaClass.simpleName}")
    }

    private fun loadComments(): List<DanmakuComment> = runBlocking {
        val api = DanmakuApi(DanmakuServices.http, DanmakuEndpoint(config.url))
        val episode = api.details(config.anime).episodes.firstOrNull { it.number == config.episode }
            ?: error("Explicit benchmark episode unavailable")
        api.comments(episode.id).also { check(it.isNotEmpty()) { "Benchmark requires real comments" } }
    }

    private fun loadPlayback(): Pair<String, Map<String, String>> = runBlocking {
        val server = LumyApp.instance.currentServer() ?: error("Benchmark requires current media server")
        val detail = server.detail(config.item)
        val version = detail.versions.getOrNull(config.version) ?: error("Benchmark version unavailable")
        check(version.parts.size == 1) { "Benchmark requires one media part" }
        val request = PlaybackRequest(detail.item, version, PlaybackMode.Direct, config.videoStartMs,
            UUID.randomUUID().toString(), audioStreamId = null, subtitleStreamId = null)
        val source = server.playbackSource(request)
        check(!source.isHls && source.urls.size == 1) { "Benchmark requires one Direct Play URL" }
        source.urls.single() to server.playbackHeaders()
    }

    private fun setup(url: String, headers: Map<String, String>) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        binding = ActivityPlayerBinding.inflate(activity.layoutInflater)
        activity.setContentView(binding.root)
        binding.controls.visibility = View.GONE
        binding.buffering.visibility = View.GONE
        bundle = PlayerFactory.create(activity, LumyApp.instance.plex.http, headers, SyncOffsets(), passthrough = false)
        binding.playerView.player = player
        binding.playerView.keepScreenOn = true
        val tracks = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        config.audioMime?.let { tracks.setPreferredAudioMimeTypes(it) }
        player.trackSelectionParameters = tracks.build()
        stats = DanmakuDeviceBenchmarkStats(activity.window)
        player.addAnalyticsListener(stats)
        player.addListener(stats)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
    }

    private fun runProfile(index: Int, profile: String, comments: List<DanmakuComment>) {
        report.write(marker("profile_prepare", index, profile))
        main {
            overlay?.release()
            player.pause()
            player.seekTo(config.videoStartMs)
        }
        await("seek ready") { player.playbackState == Player.STATE_READY && abs(player.currentPosition - config.videoStartMs) < 500 }
        await("focused player window") { activity.hasWindowFocus() && binding.playerView.isShown }
        main { applyBenchmarkAudio(); configureProfile(profile, comments) }
        awaitOverlayPrepared(profile)
        main { player.play() }
        await("video playing with fixed audio") { player.isPlaying && benchmarkAudioSelected() }
        report.write(marker("profile_warmup", index, profile))
        observe(config.warmupMs)
        val mediaReady = marker("media_ready", index, profile)
        main {
            mediaReady.put("decoder", stats.decoder).put("videoMime", player.videoFormat?.sampleMimeType)
                .put("inputWidth", player.videoFormat?.width).put("inputHeight", player.videoFormat?.height)
                .put("audioMime", player.audioFormat?.sampleMimeType).put("audioChannels", player.audioFormat?.channelCount)
                .put("audioOverrideTrackIndex", audioOverride?.trackIndices?.firstOrNull())
        }
        report.write(mediaReady)
        main { stats.validateHardware4k(player); check(benchmarkAudioSelected()) { "Benchmark fixed audio selection changed" } }
        val baseline = LongArray(3)
        main {
            val metrics = checkNotNull(overlay).metrics
            baseline[0] = metrics.update.count; baseline[1] = metrics.draw.count; baseline[2] = metrics.intervals.count
            stats.begin(player)
        }
        report.startProcessSample()
        report.write(marker("profile_start", index, profile))
        val started = SystemClock.elapsedRealtime()
        var nextReport = started + REPORT_INTERVAL_MS
        while (SystemClock.elapsedRealtime() - started < config.durationMs) {
            main { checkPlayback(); stats.sample(checkNotNull(overlay), commentPositionMs(), profile != "off") }
            if (SystemClock.elapsedRealtime() >= nextReport) {
                emit("sample", index, profile, started, baseline)
                nextReport += REPORT_INTERVAL_MS
            }
            Thread.sleep(SAMPLE_INTERVAL_MS)
        }
        main { stats.stop() }
        val summary = emit("summary", index, profile, started, baseline)
        summaries.add(summary)
        main { player.pause(); overlay?.clear() }
    }

    private fun audioMimeMatches(actual: String?): Boolean = config.audioMime == actual ||
        (config.audioMime == "audio/eac3" && actual == "audio/eac3-joc")

    private fun applyBenchmarkAudio() {
        if (config.audioMime == null) return
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val supported = groups.flatMap { group -> (0 until group.length)
            .filter { group.isTrackSupported(it) }.map { group to it } }
        if (audioOverride == null) {
            val selected = supported.firstOrNull { (group, track) -> audioMimeMatches(group.getTrackFormat(track).sampleMimeType) }
                ?: error("Benchmark audio MIME unavailable; supported=${supported.map { (g, t) -> g.getTrackFormat(t).sampleMimeType }.distinct()}")
            audioOverride = TrackSelectionOverride(selected.first.mediaTrackGroup, selected.second)
        }
        check(groups.any { it.mediaTrackGroup == audioOverride?.mediaTrackGroup }) { "Benchmark fixed audio track unavailable" }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(checkNotNull(audioOverride)).build()
    }

    private fun benchmarkAudioSelected(): Boolean {
        val selected = audioOverride ?: return config.audioMime == null
        return audioMimeMatches(player.audioFormat?.sampleMimeType) && player.currentTracks.groups.any {
            it.mediaTrackGroup == selected.mediaTrackGroup && it.isTrackSelected(selected.trackIndices.single())
        }
    }

    private fun configureProfile(profile: String, comments: List<DanmakuComment>) {
        DanmakuSettings(preferences).apply {
            enabled = profile != "off"
            density = if (profile == "high") DanmakuDensity.HIGH else DanmakuDensity.LOW
            areaPercent = 50; opacityPercent = 80; stroke = DanmakuStroke.THIN
            rolling = true; top = true; bottom = false; autoReduce = config.autoReduce
        }
        overlay = DanmakuOverlay(binding.playerView, player, ::commentPositionMs, preferences).also {
            if (profile != "off") it.setComments(comments)
        }
    }

    private fun awaitOverlayPrepared(profile: String) {
        if (profile == "off") return
        var previous = -1L
        var changedMs = SystemClock.elapsedRealtime()
        await("paused comment cache settled") {
            val comments = checkNotNull(overlay)
            val count = comments.metrics.update.count
            val now = SystemClock.elapsedRealtime()
            if (count != previous) { previous = count; changedMs = now }
            comments.appliedConfig != null && abs(comments.currentTimeMs - commentPositionMs()) < 100 &&
                now - changedMs >= 500
        }
    }

    private fun commentPositionMs() = (config.commentStartMs + player.currentPosition - config.videoStartMs).coerceAtLeast(0)

    private fun emit(event: String, index: Int, profile: String, started: Long, baseline: LongArray): String {
        val value = marker(event, index, profile).put("durationMs", SystemClock.elapsedRealtime() - started)
        main {
            val comments = checkNotNull(overlay)
            value.put("media", stats.mediaSnapshot(player)).put("positionMs", player.currentPosition)
                .put("playing", player.isPlaying).put("playbackState", player.playbackState)
                .put("windowFocused", activity.hasWindowFocus())
                .put("driftMs", if (profile == "off") 0 else comments.currentTimeMs - commentPositionMs())
                .put("commentPositionMs", commentPositionMs()).put("visible", comments.visibleCount)
                .put("densityDivisor", comments.densityDivisor).put("danmaku", JSONObject()
                    .put("updates", comments.metrics.update.count - baseline[0]).put("draws", comments.metrics.draw.count - baseline[1])
                    .put("intervals", comments.metrics.intervals.count - baseline[2])
                    .put("correctionsSinceOverlayCreated", comments.metrics.corrections)
                    .put("updateRecent256", comments.metrics.update.report()).put("drawRecent256", comments.metrics.draw.report())
                    .put("intervalRecent256", comments.metrics.intervals.report()))
            value.put("audioMime", player.audioFormat?.sampleMimeType).put("audioChannels", player.audioFormat?.channelCount)
        }
        value.put("windowFrames", stats.windowSnapshot()).put("process", report.processSnapshot())
        report.write(value)
        return value.toString()
    }

    private fun marker(event: String, index: Int, profile: String): JSONObject = JSONObject().put("event", event)
        .put("profileIndex", index).put("profile", profile).put("autoReduce", config.autoReduce)
        .put("videoStartMs", config.videoStartMs).put("commentStartMs", config.commentStartMs)
        .put("commentMinusVideoMs", config.commentStartMs - config.videoStartMs)
        .put("windowTimingDomain", "Window HWUI total duration; excludes SurfaceView presentation and GPU timing")
        .put("danmakuDistribution", "Latest 256 frames; counts are cumulative deltas, not whole-phase timing percentiles")
        .put("orderCaveat", "Sequential player reuse; later profiles may benefit from source and decoder caches")

    private fun observe(durationMs: Long) {
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < durationMs) {
            main { checkPlayback() }
            Thread.sleep(SAMPLE_INTERVAL_MS)
        }
    }

    private fun checkPlayback() {
        if (::stats.isInitialized) {
            stats.errorCode?.let { error("Benchmark player error: $it") }
            stats.codecErrorType?.let { error("Benchmark decoder error: $it") }
        }
        player.playerError?.let { error("Benchmark player error: ${it.errorCodeName}") }
        check(player.playbackState != Player.STATE_ENDED) { "Benchmark media ended before observation completed" }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            main { checkPlayback(); ready = condition() }
            if (ready) return
            Thread.sleep(50)
        }
        var audio = ""
        main { audio = "${player.audioFormat?.sampleMimeType}/${player.audioFormat?.channelCount}; fixed=${benchmarkAudioSelected()}" }
        error("Benchmark timeout: $label; audio=$audio")
    }

    private fun release() {
        overlay?.release()
        if (::stats.isInitialized) {
            player.removeAnalyticsListener(stats); player.removeListener(stats); stats.release()
        }
        if (::bundle.isInitialized) {
            binding.playerView.player = null
            player.release(); bundle.ass.release(); bundle.secondaryAss.release()
        }
        activity.finish()
    }

    private fun main(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 1_000L
        const val REPORT_INTERVAL_MS = 10_000L
    }
}

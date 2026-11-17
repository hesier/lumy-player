package com.hesier.lumy.ui.player

import com.hesier.lumy.LumyApp
import com.hesier.lumy.core.AudioStream
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.PlaybackBandwidth
import com.hesier.lumy.core.SubtitleStream
import com.hesier.lumy.data.Settings
import com.hesier.lumy.data.TrackPreferences
import com.hesier.lumy.ui.widget.attempt
import kotlinx.coroutines.launch

/** Keeps track choices, their preferences and playback-plan inputs together. */
internal class PlaybackSelection(
    val settings: Settings,
    val device: DeviceProfile,
    private val server: () -> MediaServer,
) {
    val planner = PlaybackPlanner(device)
    lateinit var version: MediaVersion
        private set
    val ready get() = this::version.isInitialized
    var audio: AudioStream? = null
    var subtitle: SubtitleStream? = null
    var secondarySubtitle: SubtitleStream? = null
    var quality: Quality = Quality.presets.getOrElse(settings.defaultQuality) { Quality.Original }
    var speed = 1f
    var plan: Plan? = null
    var fellBack = false
    var videoDecodeFailed = false
    var fallbackReason: PlaybackIssue? = null
    private var showId: String? = null
    private val serverId get() = LumyApp.instance.currentEntry?.id.orEmpty()
    private val preferences get() = TrackPreferences(settings, LumyApp.instance.trackMemory, serverId)
    val policy get() = PlanPolicy(
        settings.allowTranscode(serverId), settings.allowSubtitleBurn, settings.displaySupportsHdr,
        server().embedsSubtitles, PlaybackBandwidth.maxTotalKbps(settings.bandwidthMbps(serverId)),
    )

    fun selectVersion(version: MediaVersion, showId: String?) {
        this.version = version
        this.showId = showId
        secondarySubtitle = null
        fellBack = false
        fallbackReason = null
        videoDecodeFailed = false
        audio = preferences.audio(version, showId)
        subtitle = preferences.subtitle(version, showId, audio)
    }

    fun createPlan(): Plan = planner.plan(version, audio, subtitle, quality, policy,
        forceTranscode = fellBack, videoDecodeFailed = videoDecodeFailed, fallbackReason = fallbackReason)

    fun snapshot() = Snapshot(quality, speed, audio, subtitle, secondarySubtitle,
        fellBack, videoDecodeFailed, fallbackReason)

    fun restore(snapshot: Snapshot) = with(snapshot) {
        this@PlaybackSelection.quality = quality
        this@PlaybackSelection.speed = speed
        this@PlaybackSelection.audio = audio
        this@PlaybackSelection.subtitle = subtitle
        this@PlaybackSelection.secondarySubtitle = secondarySubtitle
        this@PlaybackSelection.fellBack = fellBack
        this@PlaybackSelection.videoDecodeFailed = videoDecodeFailed
        this@PlaybackSelection.fallbackReason = fallbackReason
    }

    data class Snapshot(
        val quality: Quality,
        val speed: Float,
        val audio: AudioStream?,
        val subtitle: SubtitleStream?,
        val secondarySubtitle: SubtitleStream?,
        val fellBack: Boolean,
        val videoDecodeFailed: Boolean,
        val fallbackReason: PlaybackIssue?,
    )

    fun rememberAudio() = preferences.rememberAudio(version, showId, audio)
    fun rememberSubtitle() = preferences.rememberSubtitle(version, showId, subtitle)

    fun saveSelection() {
        val current = version
        val audioId = audio?.id
        val subtitleId = subtitle?.id
        LumyApp.instance.appScope.launch { attempt { server().saveStreamSelection(current, audioId, subtitleId) } }
    }
}

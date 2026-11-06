@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.app.Dialog
import android.widget.Toast
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.ui.widget.addActionButton
import androidx.media3.exoplayer.ExoPlayer
import com.hesier.lumy.R
import com.hesier.lumy.core.MediaDetail
import com.hesier.lumy.core.PlaybackMode
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.data.VideoScale
import com.hesier.lumy.databinding.ActivityPlayerBinding
import com.hesier.lumy.ui.ThemedActivity
import com.hesier.lumy.ui.formatClock
import com.hesier.lumy.ui.widget.Option
import com.hesier.lumy.ui.widget.OptionsPanel
import com.hesier.lumy.ui.widget.StepperPanel

internal class PlayerMenus(
    private val activity: ThemedActivity,
    private val binding: ActivityPlayerBinding,
    private val player: ExoPlayer,
    private val selection: PlaybackSelection,
    private val server: () -> com.hesier.lumy.core.MediaServer,
    private val tracks: PlayerTrackController,
    private val info: PlayerInfo,
    private val offsets: SyncOffsets,
    private val detail: () -> MediaDetail,
    private val positionMs: () -> Long,
    private val onSeek: (Long) -> Unit,
    private val onRestart: (Boolean) -> Unit,
    private val onVersion: (MediaVersion) -> Unit,
) {
    private var playPauseIcon: ImageView? = null
    private val settings get() = selection.settings
    private var videoScale = settings.videoScale

    init { binding.playerView.resizeMode = videoScale.resizeMode() }

    fun pickQuality() {
        val options = Quality.presets.map {
            when (it) {
                Quality.Original -> Option(activity.getString(R.string.quality_original), activity.getString(R.string.quality_original_detail), selection.quality == it)
                is Quality.Limited -> Option(
                    activity.getString(R.string.quality_transcode, "${it.height}p", "%.0f".format(it.bitrateKbps / 1000f)),
                    activity.getString(R.string.quality_transcode_detail),
                    selection.quality == it,
                )
            }
        }
        OptionsPanel(activity, activity.getString(R.string.player_quality), options) { i ->
            selection.quality = Quality.presets[i]
            onRestart(true)
        }.show()
    }

    private fun pickVersion() {
        val options = detail().versions.map {
            Option(it.summary, it.bitrateKbps?.let { b -> "%.1f Mbps · %s".format(b / 1000f, it.container.orEmpty().uppercase()) }, it == selection.version)
        }
        OptionsPanel(activity, activity.getString(R.string.version_title), options) { i ->
            onVersion(detail().versions[i])
        }.returnToMore(R.string.action_version).show()
    }

    fun pickSpeed() {
        // Only an active passthrough route prevents changing playback speed.
        if (info.passthroughEncoding != null) {
            Toast.makeText(activity, R.string.speed_unavailable_passthrough, Toast.LENGTH_LONG).show()
            return
        }
        val options = SPEEDS.map { Option(if (it == 1f) activity.getString(R.string.speed_normal) else "${it}x", selected = it == selection.speed) }
        OptionsPanel(activity, activity.getString(R.string.player_speed), options,
            hint = activity.getString(R.string.player_hold_speed_hint)) { i ->
            selection.speed = SPEEDS[i]
            player.setPlaybackSpeed(selection.speed)
        }.show()
    }

    private fun pickChapter() {
        val chapters = detail().chapters
        val current = chapters.indexOfLast { positionMs() >= it.startMs }.coerceAtLeast(0)
        val options = chapters.mapIndexed { i, c ->
            Option("${i + 1}. ${c.title ?: activity.getString(R.string.chapter_n, i + 1)}", formatClock(c.startMs), i == current)
        }
        OptionsPanel(activity, activity.getString(R.string.player_chapters), options) { onSeek(chapters[it].startMs) }
            .returnToMore(R.string.player_chapters).show()
    }

    fun showMore(focusedLabel: Int? = null) {
        val actions = buildList<Pair<Option, () -> Unit>> {
            if (detail().chapters.size > 1) add(Option(activity.getString(R.string.player_chapters)) to ::pickChapter)
            if (detail().versions.size > 1) add(Option(activity.getString(R.string.action_version), selection.version.summary) to ::pickVersion)
            add(Option(activity.getString(R.string.video_scale), videoScaleLabel(activity, videoScale)) to ::pickVideoScale)
            if (selection.version.subtitles.isNotEmpty()) add(Option(activity.getString(R.string.player_secondary_subtitles),
                selection.secondarySubtitle?.title ?: activity.getString(R.string.subtitles_off)) to ::pickSecondarySubtitle)
            if (selection.secondarySubtitle?.codec?.lowercase() in setOf("srt", "subrip", "vtt", "webvtt")) {
                add(Option(activity.getString(R.string.player_secondary_subtitle_style)) to {
                    SecondarySubtitleStylePanel(activity, settings, tracks.dual::refreshStyle,
                        onBack = { showMore(R.string.player_secondary_subtitle_style) }).show()
                })
            }
            add(Option(activity.getString(R.string.subtitle_delay), formatDelay(activity, offsets.subtitleDelayMs)) to ::adjustSubtitleDelay)
            add(Option(activity.getString(R.string.audio_delay), formatDelay(activity, offsets.audioDelayMs)) to ::adjustAudioDelay)
            val infoLabel = if (info.visible) R.string.info_hide else R.string.info_show
            add(Option(activity.getString(infoLabel)) to info::toggle)
        }
        OptionsPanel(activity, activity.getString(R.string.player_more), actions.map {
            it.first.copy(selected = focusedLabel != null && it.first.label == activity.getString(focusedLabel))
        }) { actions[it].second() }.show()
    }

    fun Dialog.returnToMore(label: Int): Dialog {
        val mediaId = detail().item.id
        val versionId = selection.version.id
        setOnCancelListener {
            if (!activity.isFinishing && !activity.isDestroyed && detail().item.id == mediaId && selection.version.id == versionId) showMore(label)
        }
        return this
    }

    /** Keep this override for the current playback session, including following episodes. */
    private fun pickVideoScale() {
        val scales = VideoScale.entries
        val options = scales.map { Option(videoScaleLabel(activity, it), selected = it == videoScale) }
        OptionsPanel(activity, activity.getString(R.string.video_scale), options) { i ->
            videoScale = scales[i]
            binding.playerView.resizeMode = videoScale.resizeMode()
        }.returnToMore(R.string.video_scale).show()
    }

    private fun adjustSubtitleDelay() {
        StepperPanel(
            activity, activity.getString(R.string.subtitle_delay), activity.getString(R.string.delay_hint), offsets.subtitleDelayMs,
            100, 1_000, -10_000L..10_000L, { formatDelay(activity, it) },
        ) { offsets.subtitleDelayMs = it }.returnToMore(R.string.subtitle_delay).show()
    }

    /** Persist audio delay because it normally depends on the output device. */
    private fun adjustAudioDelay() {
        StepperPanel(
            activity, activity.getString(R.string.audio_delay), activity.getString(R.string.delay_hint), offsets.audioDelayMs,
            100, 1_000, -5_000L..5_000L, { formatDelay(activity, it) },
        ) {
            offsets.audioDelayMs = it
            settings.audioDelayMs = it
        }.returnToMore(R.string.audio_delay).show()
    }

    private fun renderControls(onPlay: () -> Unit, onDanmaku: () -> Unit) {
        binding.title.text = if (detail().item.type == MediaType.EPISODE) detail().item.showTitle ?: detail().item.title else detail().item.title
        binding.subtitle.text = when (detail().item.type) {
            MediaType.EPISODE -> listOfNotNull(
                detail().item.seasonNumber?.let { s -> detail().item.episodeNumber?.let { e -> activity.getString(R.string.episode_code, s, e) } },
                detail().item.title,
            ).joinToString("  ·  ")
            else -> detail().item.year?.toString()
        }
        val row = binding.buttons
        row.removeAllViews()
        val playButton = row.addActionButton(R.drawable.ic_pause, "") { onPlay() }
        playButton.findViewById<TextView>(R.id.label).visibility = View.GONE
        playPauseIcon = playButton.findViewById(R.id.icon)
        if (selection.version.subtitles.isNotEmpty()) row.addActionButton(R.drawable.ic_subtitles, activity.getString(R.string.player_subtitles)) { pickSubtitle() }
        if (selection.version.audio.size > 1) row.addActionButton(R.drawable.ic_audio, activity.getString(R.string.player_audio)) { pickAudio() }
        if (selection.policy.allowTranscode) row.addActionButton(R.drawable.ic_hd, activity.getString(R.string.player_quality)) { pickQuality() }
        row.addActionButton(R.drawable.ic_speed, activity.getString(R.string.player_speed)) { pickSpeed() }
        if (settings.danmaku.enabled) row.addActionButton(R.drawable.ic_danmaku, activity.getString(R.string.settings_section_danmaku)) {
            onDanmaku()
        }
        row.addActionButton(R.drawable.ic_more, activity.getString(R.string.player_more)) { showMore() }
        binding.seek.setOnFocusChangeListener { _, hasFocus ->
            binding.seekBar.animate().scaleY(if (hasFocus) 1.6f else 1f).setDuration(120).start()
        }
    }

    fun refreshControls(onPlay: () -> Unit, onDanmaku: () -> Unit, restoreFocus: Boolean) {
        val hadFocus = restoreFocus && binding.buttons.hasFocus()
        val label = binding.buttons.focusedChild?.findViewById<TextView>(R.id.label)?.text
        renderControls(onPlay, onDanmaku)
        if (restoreFocus) onPlayingChanged(player.isPlaying)
        if (hadFocus) {
            val target = (0 until binding.buttons.childCount).map { binding.buttons.getChildAt(it) }
                .firstOrNull { it.findViewById<TextView>(R.id.label)?.text == label }
            (target ?: binding.buttons.getChildAt(0))?.requestFocus()
        }
    }

    fun onPlayingChanged(isPlaying: Boolean) {
        playPauseIcon?.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    fun pickSecondarySubtitle() {
        val streams = selection.version.subtitles.filter { it.id != selection.subtitle?.id }
        val options = listOf(Option(activity.getString(R.string.subtitles_off), selected = selection.secondarySubtitle == null)) +
            streams.map { stream ->
                val available = tracks.findSubtitleGroup(stream)?.isSupported == true ||
                    (stream.external && stream.codec.lowercase() in SupMediaSourceFactory.CODECS &&
                        (selection.plan?.mode as? PlaybackMode.Transcode)?.burnSubtitleId == null)
                Option(stream.title, listOfNotNull(stream.codec.uppercase(),
                    activity.getString(R.string.subtitle_not_available_note).takeUnless { available }).joinToString(" · "),
                    stream.id == selection.secondarySubtitle?.id)
            }
        OptionsPanel(activity, activity.getString(R.string.player_secondary_subtitles), options) { index ->
            val selected = streams.getOrNull(index - 1)
            if (selected?.id == selection.secondarySubtitle?.id) return@OptionsPanel
            val pgs = selected?.codec?.lowercase() in SupMediaSourceFactory.CODECS
            if (selected != null && tracks.findSubtitleGroup(selected)?.isSupported != true &&
                !(pgs && selected.external && (selection.plan?.mode as? PlaybackMode.Transcode)?.burnSubtitleId == null)) {
                Toast.makeText(activity, R.string.subtitle_unavailable, Toast.LENGTH_LONG).show()
                return@OptionsPanel
            }
            val rebuildCodecs = SupMediaSourceFactory.CODECS + setOf("ass", "ssa")
            val rebuild = selected?.codec?.lowercase() in rebuildCodecs || selection.secondarySubtitle?.codec?.lowercase() in rebuildCodecs
            selection.secondarySubtitle = selected
            tracks.dual.onSecondaryCues(androidx.media3.common.text.CueGroup(emptyList(), 0))
            if (rebuild) onRestart(player.playWhenReady) else tracks.apply()
        }.returnToMore(R.string.player_secondary_subtitles).show()
    }

    fun pickAudio() {
        val options = selection.version.audio.map { Option(it.title, it.channelsLabel, it == selection.audio) }
        OptionsPanel(activity, activity.getString(R.string.player_audio), options) { i ->
            selection.audio = selection.version.audio[i]
            selection.saveSelection()
            selection.rememberAudio()
            if (selection.plan?.mode is PlaybackMode.Direct) tracks.apply() else onRestart(true)
        }.show()
    }

    fun pickSubtitle(focusStyle: Boolean = false) {
        val transcoding = selection.plan?.mode is PlaybackMode.Transcode
        // Already reencoded video can burn unsupported subtitles on the server.
        val reencoding = (selection.plan?.mode as? PlaybackMode.Transcode)?.copyVideo == false
        val options = listOf(Option(activity.getString(R.string.subtitles_off), selected = selection.subtitle == null)) +
            selection.version.subtitles.map {
                val note = when {
                    selection.device.canRenderSubtitle(it, transcoding) -> null
                    transcoding && selection.planner.canEmbed(it, selection.policy) -> null
                    selection.policy.allowTranscode && (selection.policy.allowSubtitleBurn || reencoding) -> activity.getString(R.string.subtitle_burn_note)
                    else -> activity.getString(R.string.subtitle_not_available_note)
                }
                Option(it.title, listOfNotNull(it.codec.uppercase(), if (it.forced) activity.getString(R.string.subtitle_forced) else null, note).joinToString(" · "), it == selection.subtitle)
            }
        OptionsPanel(activity, activity.getString(R.string.player_subtitles), options) { i ->
            val selected = if (i == 0) null else selection.version.subtitles[i - 1]
            // Native HLS contains only the subtitle selected when its session was created.
            val nativeSubtitleChanged = server().session.kind == ServerKind.FNOS && transcoding && selected?.id != selection.subtitle?.id
            val oldPgs = selection.subtitle?.takeIf { it.codec.lowercase() in SupMediaSourceFactory.CODECS }?.id
            val newPgs = selected?.takeIf { it.codec.lowercase() in SupMediaSourceFactory.CODECS }?.id
            if (selected?.id != selection.subtitle?.id) tracks.bitmap.clear()
            if (selected?.id != selection.subtitle?.id && selection.fallbackReason is PlaybackIssue.SubtitleNeedsBurn) {
                selection.fallbackReason = null
                selection.fellBack = false
            }
            selection.subtitle = selected
            if (selection.secondarySubtitle?.id == selected?.id) selection.secondarySubtitle = null
            selection.saveSelection()
            selection.rememberSubtitle()
            // Skipped PGS tracks have no decoded cues; rebuild their source when changing tracks.
            val newPlan = selection.createPlan()
            val sameMode = newPlan.mode == selection.plan?.mode
            if (sameMode && oldPgs == newPgs && !nativeSubtitleChanged) tracks.apply()
            else onRestart(!sameMode || player.playWhenReady)
        }.withHeaderAction(activity.getString(R.string.player_subtitle_style), focused = focusStyle) {
            showSubtitleStyle()
        }.show()
    }

    private fun showSubtitleStyle() {
        val authoredCodecs = SupMediaSourceFactory.CODECS + setOf("ass", "ssa", "dvd_subtitle", "vobsub", "dvb_subtitle")
        val burned = selection.subtitle != null && (selection.plan?.mode as? PlaybackMode.Transcode)?.burnSubtitleId == selection.subtitle?.id
        val adjustable = !burned && selection.subtitle?.codec?.lowercase() !in authoredCodecs
        lateinit var panel: SubtitleStylePanel
        val secondaryStyle: (() -> Unit)? = if (selection.secondarySubtitle?.codec?.lowercase() in setOf("srt", "subrip", "vtt", "webvtt")) {
            {
                SecondarySubtitleStylePanel(activity, settings, tracks::refreshSubtitleStyle,
                    onBack = { panel.show(Int.MAX_VALUE) }).show()
            }
        } else null
        panel = SubtitleStylePanel(activity, settings, tracks::refreshSubtitleStyle, onBack = { pickSubtitle(focusStyle = true) },
            onSecondaryStyle = secondaryStyle, allowPositionAndBackground = adjustable, allowScale = adjustable)
        panel.show()
    }

    private companion object {
        val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    }
}

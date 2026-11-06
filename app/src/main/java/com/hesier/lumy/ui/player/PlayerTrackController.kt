@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.graphics.Color
import android.view.View
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.hesier.lumy.R
import com.hesier.lumy.core.PlaybackMode
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.SubtitleStream
import com.hesier.lumy.data.Settings
import com.hesier.lumy.databinding.ActivityPlayerBinding
import com.hesier.lumy.ui.ThemedActivity
import io.github.peerless2012.ass.media.widget.AssSubtitleView

internal class PlayerTrackController(
    private val activity: ThemedActivity,
    bundle: PlayerBundle,
    private val binding: ActivityPlayerBinding,
    private val selection: PlaybackSelection,
    private val server: () -> com.hesier.lumy.core.MediaServer,
    private val onRestart: (Boolean) -> Unit,
) {
    private val player = bundle.player
    private val ass = bundle.ass
    private val secondaryAss = bundle.secondaryAss
    val selector = bundle.subtitles
    val bitmap: BitmapSubtitleOverlay
    val dual: DualSubtitleOverlay
    private val settings get() = selection.settings
    private val subtitlePadding get() = Settings.SUBTITLE_POSITIONS[settings.subtitlePosition]

    init {
        applySubtitleStyle()
        binding.playerView.subtitleView?.addView(AssSubtitleView(activity, ass))
        bitmap = BitmapSubtitleOverlay(binding.playerView)
        player.addListener(bitmap)
        dual = DualSubtitleOverlay(binding.playerView, selection.settings, secondaryAss)
        selector.onCues = dual::onSecondaryCues
        player.addListener(dual)
    }

    fun prepare() {
        bitmap.clear()
        dual.clear()
        selector.selectSecondary(null)
        selector.secondaryAssEnabled = selection.secondarySubtitle?.codec?.lowercase() in setOf("ass", "ssa")
    }

    fun setControlsVisible(visible: Boolean) {
        val padding = if (visible) maxOf(subtitlePadding, SUBTITLE_PADDING_WITH_CONTROLS) else subtitlePadding
        binding.playerView.subtitleView?.setBottomPaddingFraction(padding)
        dual.setBottomPaddingFraction(padding)
    }

    fun release() {
        selector.onCues = null
        ass.release()
        secondaryAss.release()
    }

    fun apply() {
        val mode = selection.plan?.mode ?: return
        val groups = player.currentTracks.groups
        val params = player.trackSelectionParameters.buildUpon()

        if (mode is PlaybackMode.Direct) {
            val audioGroups = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            matchGroup(audioGroups, selection.audio?.index, selection.version.audio.indexOf(selection.audio))?.let {
                params.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0))
            }
        }

        val s = selection.subtitle
        val burned = (mode as? PlaybackMode.Transcode)?.burnSubtitleId
        if (s == null || burned == s.id || selection.plan?.subtitleUnavailable == true) {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val textGroups = groups.filter { it.type == C.TRACK_TYPE_TEXT }
            val group = if (s.external) {
                textGroups.firstOrNull { it.getTrackFormat(0).id?.contains(EXTERNAL_PREFIX + s.id) == true }
            } else if ((mode as? PlaybackMode.Transcode)?.embedSubtitleId == s.id ||
                (mode is PlaybackMode.Transcode && server().session.kind == ServerKind.FNOS)) {
                // A transcode contains only the selected embedded subtitle, with different IDs.
                textGroups.firstOrNull { it.getTrackFormat(0).id?.contains(EXTERNAL_PREFIX) != true }
            } else {
                val embedded = textGroups.filter { it.getTrackFormat(0).id?.contains(EXTERNAL_PREFIX) != true }
                matchGroup(embedded, s.index, selection.version.subtitles.filter { !it.external }.indexOf(s))
            }
            if (group != null) {
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            } else if (mode is PlaybackMode.Direct && selection.policy.allowTranscode && selection.policy.allowSubtitleBurn) {
                // Burn an unrecognized subtitle only when the user allows server transcoding.
                selection.fellBack = true
                selection.fallbackReason = PlaybackIssue.SubtitleNeedsBurn(s.codec)
                onRestart(true)
                return
            } else {
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                Toast.makeText(activity, R.string.subtitle_unavailable, Toast.LENGTH_LONG).show()
            }
        }
        player.trackSelectionParameters = params.build()
        if (selection.secondarySubtitle?.id == selection.subtitle?.id) selection.secondarySubtitle = null
        selector.selectSecondary(selection.secondarySubtitle?.let(::findSubtitleGroup)?.mediaTrackGroup)
    }

    fun findSubtitleGroup(stream: SubtitleStream): Tracks.Group? {
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (stream.external) return groups.firstOrNull {
            it.getTrackFormat(0).id?.contains(EXTERNAL_PREFIX + stream.id) == true
        }
        val embedded = groups.filter { it.getTrackFormat(0).id?.contains(EXTERNAL_PREFIX) != true }
        val mode = selection.plan?.mode
        if (mode is PlaybackMode.Transcode) {
            val nativeText = server().session.kind == ServerKind.FNOS && selection.subtitle?.id == stream.id &&
                mode.burnSubtitleId != stream.id
            return embedded.firstOrNull().takeIf { mode.embedSubtitleId == stream.id || nativeText }
        }
        return matchGroup(embedded, stream.index, selection.version.subtitles.filter { !it.external }.indexOf(stream))
    }

    private fun matchGroup(groups: List<Tracks.Group>, streamIndex: Int?, position: Int): Tracks.Group? {
        if (streamIndex != null) {
            val trackNumber = (streamIndex + 1).toString()
            // Merging an external subtitle prefixes embedded track IDs with the source index.
            groups.firstOrNull { it.getTrackFormat(0).id?.substringAfterLast(':') == trackNumber }?.let { return it }
        }
        return groups.getOrNull(position)
    }

    private fun applySubtitleStyle() {
        binding.playerView.subtitleView?.apply {
            val background = if (settings.subtitleBackground) SUBTITLE_BACKGROUND else Color.TRANSPARENT
            setStyle(CaptionStyleCompat(Color.WHITE, background, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null))
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.1f * settings.subtitleScale)
            setBottomPaddingFraction(if (binding.controls.visibility == View.VISIBLE) maxOf(subtitlePadding, SUBTITLE_PADDING_WITH_CONTROLS) else subtitlePadding)
        }
    }

    fun refreshSubtitleStyle() {
        applySubtitleStyle()
        dual.refreshStyle()
        setControlsVisible(binding.controls.visibility == View.VISIBLE)
    }

    private companion object {
        const val EXTERNAL_PREFIX = "ext:"
        val SUBTITLE_BACKGROUND = Color.argb(0x99, 0, 0, 0)
        const val SUBTITLE_PADDING_WITH_CONTROLS = 0.36f
    }
}

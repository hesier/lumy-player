@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import coil.load
import com.hesier.lumy.R
import com.hesier.lumy.core.Marker
import com.hesier.lumy.core.MarkerType
import com.hesier.lumy.core.MediaDetail
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.data.Settings
import com.hesier.lumy.data.SkipMode
import com.hesier.lumy.databinding.ActivityPlayerBinding
import com.hesier.lumy.ui.ThemedActivity
import com.hesier.lumy.ui.widget.clipToRoundedBackground
import com.hesier.lumy.util.DebugLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Owns marker prompts and countdowns, with playback actions supplied by the Activity. */
internal class PlayerOverlayController(
    private val activity: ThemedActivity,
    private val binding: ActivityPlayerBinding,
    private val player: Player,
    private val settings: Settings,
    private val positionMs: () -> Long,
    private val durationMs: () -> Long,
    private val nextEpisode: () -> MediaItem?,
    private val onSeek: (Long) -> Unit,
    private val onEnded: () -> Unit,
    private val onPlayNext: () -> Unit,
) {
    private lateinit var server: MediaServer
    private lateinit var detail: MediaDetail
    private val autoSkipped = HashSet<Marker>()
    private var markerJob: Job? = null
    private var upNextJob: Job? = null
    private var skipTarget: Long? = null
    private val controlsVisible get() = binding.controls.visibility == View.VISIBLE

    var upNextDismissed = false
        private set
    val hasFocus get() = binding.skip.hasFocus() || binding.upNext.hasFocus()
    val upNextVisible get() = binding.upNext.visibility == View.VISIBLE
    val upNextHasFocus get() = binding.upNext.hasFocus()

    init {
        binding.upNextFrame.clipToRoundedBackground()
        binding.upNextPlay.setOnClickListener { onPlayNext() }
        binding.upNextCancel.setOnClickListener { dismissUpNext() }
    }

    fun bind(server: MediaServer, detail: MediaDetail) {
        this.server = server
        this.detail = detail
        markerJob?.cancel()
        markerJob = activity.lifecycleScope.launch {
            while (isActive) {
                delay(MARKER_POLL_MS)
                if (player.playbackState == Player.STATE_READY) checkMarkers()
            }
        }
    }

    fun reset() {
        markerJob?.cancel()
        hideOverlays()
        autoSkipped.clear()
        upNextDismissed = false
    }

    private fun checkMarkers() {
        val pos = positionMs()
        val duration = durationMs()
        val active = detail.markers.firstOrNull { pos >= it.startMs && pos < it.endMs - 1_000 }
        val next = nextEpisode()?.takeIf { settings.autoPlayNext && !upNextDismissed }
        val inCredits = active?.type == MarkerType.CREDITS ||
            (detail.markers.none { it.type == MarkerType.CREDITS } && duration > 0 && duration - pos in 1..UP_NEXT_FALLBACK_MS)
        if (next != null && inCredits) {
            showUpNext(next)
            return
        }
        // Seeking out of credits hides the card without dismissing it permanently.
        if (upNextVisible) {
            upNextJob?.cancel()
            binding.upNext.visibility = View.GONE
        }
        val mode = when (active?.type) {
            MarkerType.INTRO -> settings.skipIntro
            MarkerType.CREDITS -> settings.skipCredits
            null -> SkipMode.OFF
        }
        when {
            active == null || mode == SkipMode.OFF -> hideSkip()
            mode == SkipMode.AUTO -> if (autoSkipped.add(active)) {
                hideSkip()
                skipTo(active.endMs)
            }
            else -> showSkip(active)
        }
    }

    private fun showSkip(marker: Marker) {
        skipTarget = marker.endMs
        if (binding.skip.visibility == View.VISIBLE || controlsVisible) return
        binding.skipLabel.setText(if (marker.type == MarkerType.INTRO) R.string.skip_intro else R.string.skip_credits)
        binding.skip.visibility = View.VISIBLE
        binding.skip.setOnClickListener { skipTarget?.let(::skipTo); hideSkip() }
        binding.skip.requestFocus()
    }

    fun hideSkip() {
        if (binding.skip.visibility != View.VISIBLE) return
        binding.skip.visibility = View.GONE
        skipTarget = null
    }

    private fun skipTo(target: Long) {
        val duration = durationMs()
        if (duration > 0 && target >= duration - 2_000) onEnded() else onSeek(target)
    }

    private fun showUpNext(next: MediaItem) {
        if (upNextVisible || controlsVisible) return
        DebugLog.i(TAG, "up next ${next.id} at ${positionMs()}")
        hideSkip()
        binding.upNextLabel.setText(if (next.type == MediaType.EPISODE) R.string.up_next else R.string.up_next_item)
        binding.upNextTitle.text = next.title
        binding.upNextSub.text = next.seasonNumber?.let { s -> next.episodeNumber?.let { e -> activity.getString(R.string.episode_code, s, e) } }
        val frame = binding.upNextFrame.layoutParams
        (next.thumb ?: next.backdrop ?: next.poster)?.let { binding.upNextThumb.load(server.imageUrl(it, frame.width, frame.height)) }
        binding.upNext.visibility = View.VISIBLE
        binding.upNextPlay.requestFocus()
        upNextJob?.cancel()
        upNextJob = activity.lifecycleScope.launch {
            for (left in UP_NEXT_SECONDS downTo 1) {
                binding.upNextPlay.text = activity.getString(R.string.play_now) + " · " + activity.getString(R.string.up_next_countdown, left)
                delay(1_000)
                // Pausing playback also pauses the countdown.
                while (!player.isPlaying) delay(300)
            }
            onPlayNext()
        }
    }

    fun dismissUpNext() {
        upNextDismissed = true
        upNextJob?.cancel()
        binding.upNext.visibility = View.GONE
    }

    private fun hideOverlays() {
        upNextJob?.cancel()
        binding.upNext.visibility = View.GONE
        hideSkip()
    }

    private companion object {
        const val TAG = "LumyPlayer"
        const val MARKER_POLL_MS = 500L
        const val UP_NEXT_SECONDS = 10
        const val UP_NEXT_FALLBACK_MS = 20_000L
    }
}

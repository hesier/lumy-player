package com.hesier.lumy.ui.player

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.hesier.lumy.R
import com.hesier.lumy.databinding.ActivityPlayerBinding
import com.hesier.lumy.ui.ThemedActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class PlayerControls(
    private val activity: ThemedActivity,
    private val binding: ActivityPlayerBinding,
    private val tracks: PlayerTrackController,
    private val episodes: PlayerEpisodeController,
    private val overlays: PlayerOverlayController,
    private val seek: PlayerSeekController,
    private val durationMs: () -> Long,
    private val isReady: () -> Boolean,
    private val onShow: () -> Unit,
) {
    private var controlsJob: Job? = null

    init { setupTouch() }

    val visible get() = binding.controls.visibility == View.VISIBLE

    enum class Focus { SEEK, BUTTONS, NONE }

    fun show(target: Focus) {
        onShow()
        autoHiddenAt = 0L
        if (!visible) {
            // Marker polling restores the skip prompt after the controls hide.
            overlays.hideSkip()
            binding.controlsHeader.visibility = View.VISIBLE
            binding.controlsHeader.alpha = 0f
            binding.controlsHeader.animate().alpha(1f).setDuration(150).start()
            binding.controls.visibility = View.VISIBLE
            binding.controls.alpha = 0f
            binding.controls.animate().alpha(1f).setDuration(150).start()
            // Raise subtitles above the visible controls.
            tracks.setControlsVisible(true)
            when (target) {
                Focus.SEEK -> binding.seek.requestFocus()
                // Default to the play/pause button.
                Focus.BUTTONS -> binding.buttons.getChildAt(0)?.requestFocus()
                Focus.NONE -> Unit
            }
            controlsJob?.cancel()
            controlsJob = activity.lifecycleScope.launch {
                while (isActive) {
                    seek.updateProgress()
                    delay(500)
                }
            }
        }
        scheduleHide()
    }

    fun hide() {
        hideJob?.cancel()
        controlsJob?.cancel()
        binding.controlsHeader.visibility = View.GONE
        binding.controls.visibility = View.GONE
        episodes.hideRow()
        tracks.setControlsVisible(false)
        seek.commit()
    }

    private var hideJob: Job? = null

    /** Guard Back briefly after auto-hide to avoid accidentally exiting playback. */
    private var autoHiddenAt = 0L
    private var exitConfirmUntil = 0L

    fun scheduleHide() {
        hideJob?.cancel()
        if (!visible || !activity.hasWindowFocus()) return
        hideJob = activity.lifecycleScope.launch {
            delay(3_000)
            if (visible && activity.hasWindowFocus() && seek.scrubTarget == null) {
                hide()
                autoHiddenAt = SystemClock.elapsedRealtime()
            }
        }
    }

    /** Require a second Back press shortly after the controls auto-hide. */
    fun guardBackAfterAutoHide(down: Boolean): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (autoHiddenAt == 0L || now - autoHiddenAt > BACK_GUARD_MS || now < exitConfirmUntil) return false
        if (!down) {
            exitConfirmUntil = now + BACK_GUARD_MS
            Toast.makeText(activity, R.string.player_back_again_exit, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        // PlayerView has no built-in controller, so recognize surface taps here.
        val taps = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (binding.state.root.visibility == View.VISIBLE || !isReady()) return false
                if (visible) hide() else show(Focus.NONE)
                return true
            }
        })
        binding.playerView.setOnTouchListener { _, e -> taps.onTouchEvent(e) }
        // Consume taps between buttons so they do not hide the controls underneath.
        binding.controls.isClickable = true
        // Keep the clickable background out of D-pad focus navigation.
        binding.controls.isFocusable = false
        binding.seek.setOnTouchListener { _, e ->
            val duration = durationMs()
            if (duration <= 0) return@setOnTouchListener true
            val bar = binding.seekBar
            val fraction = ((e.x - bar.left) / bar.width).coerceIn(0f, 1f)
            when (e.actionMasked) {
                // Preview the drag target and seek only when released.
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    seek.preview(fraction)
                    scheduleHide()
                }
                MotionEvent.ACTION_UP -> {
                    seek.commit()
                    scheduleHide()
                }
                MotionEvent.ACTION_CANCEL -> {
                    seek.cancel()
                    seek.updateProgress()
                    scheduleHide()
                }
            }
            true
        }
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) scheduleHide() else hideJob?.cancel()
    }

    fun release() {
        controlsJob?.cancel()
        hideJob?.cancel()
        seek.cancel()
        seek.clearHint()
    }

    private companion object {
        const val BACK_GUARD_MS = 3_000L
    }
}

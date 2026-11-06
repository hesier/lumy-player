package com.hesier.lumy.ui.player

import android.view.View
import androidx.lifecycle.lifecycleScope
import com.hesier.lumy.R
import com.hesier.lumy.data.Settings
import com.hesier.lumy.databinding.ActivityPlayerBinding
import com.hesier.lumy.ui.ThemedActivity
import com.hesier.lumy.ui.formatClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

internal class PlayerSeekController(
    private val activity: ThemedActivity,
    private val binding: ActivityPlayerBinding,
    private val settings: Settings,
    private val positionMs: () -> Long,
    private val durationMs: () -> Long,
    private val bufferedMs: () -> Long,
    private val onSeek: (Long) -> Unit,
    private val needsRestart: (Long) -> Boolean,
    private val onInteraction: () -> Unit,
) {
    private var scrubJob: Job? = null
    private var hintJob: Job? = null
    var scrubTarget: Long? = null
        private set

    fun updateProgress() {
        val duration = durationMs().takeIf { it > 0 } ?: return
        val shown = scrubTarget ?: positionMs()
        binding.seekBar.progress = (shown * 10_000 / duration).toInt()
        binding.seekBar.secondaryProgress = (bufferedMs() * 10_000 / duration).toInt()
        binding.position.text = formatClock(positionMs())
        binding.remaining.text = if (settings.showRemainingTime) {
            "-" + formatClock((duration - shown).coerceAtLeast(0))
        } else {
            formatClock(duration)
        }
        binding.scrub.visibility = if (scrubTarget != null) View.VISIBLE else View.INVISIBLE
        binding.scrub.text = scrubTarget?.let { formatClock(it) }
    }

    /** Delay seeks during remote repeats to avoid requesting each intermediate position. */
    fun scrub(forward: Boolean, repeat: Int) {
        val baseStep = if (forward) settings.seekForwardMs else settings.seekBackMs
        val step = maxOf(baseStep, when {
            repeat > 20 -> 120_000L
            repeat > 8 -> 60_000L
            repeat > 2 -> 30_000L
            else -> baseStep
        })
        val base = scrubTarget ?: positionMs()
        scrubTarget = (base + if (forward) step else -step).coerceIn(0, durationMs())
        updateProgress()
        scrubJob?.cancel()
        scrubJob = activity.lifecycleScope.launch {
            delay(800)
            commit()
        }
        onInteraction()
    }

    fun commit() {
        val target = scrubTarget ?: return
        cancel()
        onSeek(target)
        updateProgress()
    }

    fun cancel() {
        scrubJob?.cancel()
        scrubJob = null
        scrubTarget = null
    }

    /** Accumulate the displayed delta while consecutive skip hints overlap. */
    private var skipTotalMs = 0L

    fun skip(deltaMs: Long) {
        val target = ((scrubTarget ?: positionMs()) + deltaMs)
            .coerceIn(0, (durationMs() - 1_000).coerceAtLeast(0))
        cancel()
        if (needsRestart(target)) {
            // Coalesce remote repeats before replacing a server transcode session.
            scrubTarget = target
            scrubJob = activity.lifecycleScope.launch {
                delay(TRANSCODE_SKIP_DELAY_MS)
                commit()
            }
        } else onSeek(target)
        updateProgress()
        if (binding.hint.visibility != View.VISIBLE) skipTotalMs = 0
        skipTotalMs += deltaMs
        val total = skipTotalMs
        val amount = formatSkip(abs(total))
        showHint(if (total < 0) "‹ -$amount" else "+$amount ›")
    }

    private fun formatSkip(ms: Long): String = activity.getString(R.string.skip_seconds, (ms / 1000).toInt())

    private fun showHint(text: String) {
        binding.hint.text = text
        binding.hint.visibility = View.VISIBLE
        hintJob?.cancel()
        hintJob = activity.lifecycleScope.launch {
            delay(900)
            binding.hint.visibility = View.GONE
        }
    }

    fun preview(fraction: Float) {
        scrubJob?.cancel()
        scrubTarget = (durationMs() * fraction).toLong()
        updateProgress()
    }

    fun showHoldSpeed(speed: Float) {
        hintJob?.cancel()
        skipTotalMs = 0L
        binding.hint.text = activity.getString(R.string.player_hold_speed, speed.toString())
        binding.hint.visibility = View.VISIBLE
    }

    fun clearHint() {
        hintJob?.cancel()
        binding.hint.visibility = View.GONE
    }

    private companion object {
        const val TRANSCODE_SKIP_DELAY_MS = 300L
    }
}

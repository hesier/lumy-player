package com.hesier.lumy.ui.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A failed user quality change can restore the last working selection only once. */
internal class QualitySwitchRecovery(
    private val scope: CoroutineScope,
    private val onTimeout: () -> Unit,
    private val onResult: (Boolean) -> Unit,
) {
    data class Previous(
        val selection: PlaybackSelection.Snapshot,
        val positionMs: Long,
        val playWhenReady: Boolean,
        val hevcFallback: Boolean,
        val softwareVideoMime: String?,
    )

    private var previous: Previous? = null
    private var timeout: Job? = null
    var restoring = false
        private set
    val active get() = previous != null

    fun begin(previous: Previous) {
        clear()
        this.previous = previous
        armTimeout()
    }

    fun rollback(): Previous? {
        val saved = previous ?: return null
        if (restoring) {
            clear()
            onResult(false)
            return null
        }
        restoring = true
        armTimeout()
        return saved
    }

    fun ready() {
        if (!active) return
        val recovered = restoring
        clear()
        if (recovered) onResult(true)
    }

    fun clear() {
        timeout?.cancel()
        timeout = null
        previous = null
        restoring = false
    }

    private fun armTimeout() {
        timeout?.cancel()
        timeout = scope.launch {
            delay(START_TIMEOUT_MS)
            onTimeout()
        }
    }

    private companion object {
        const val START_TIMEOUT_MS = 30_000L
    }
}

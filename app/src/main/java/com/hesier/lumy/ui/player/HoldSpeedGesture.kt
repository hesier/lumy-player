package com.hesier.lumy.ui.player

import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps a confirm press owned until release, even if playback or focus changes. */
internal class HoldSpeedGesture(
    private val scope: CoroutineScope,
    private val onTap: () -> Unit,
    private val onHold: () -> Unit,
    private val onRelease: () -> Unit,
) {
    private var keyCode: Int? = null
    private var holdJob: Job? = null
    private var holding = false
    private var tapAllowed = false

    fun handle(event: KeyEvent, enabled: Boolean): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && event.keyCode in CONFIRM_KEYS) {
            cancel()
            keyCode = null
        }
        if (keyCode != null && event.keyCode != keyCode) cancel()
        if (event.keyCode == keyCode) {
            if (event.action == KeyEvent.ACTION_UP) {
                val tap = tapAllowed && !holding && !event.isCanceled
                cancel()
                keyCode = null
                if (tap) onTap()
            }
            return true
        }
        if (!enabled || event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0 ||
            event.keyCode !in CONFIRM_KEYS) return false
        keyCode = event.keyCode
        tapAllowed = true
        holdJob = scope.launch {
            delay(HOLD_DELAY_MS)
            holding = true
            onHold()
        }
        return true
    }

    fun cancel() {
        holdJob?.cancel()
        holdJob = null
        tapAllowed = false
        if (holding) {
            holding = false
            onRelease()
        }
    }

    companion object {
        const val HOLD_DELAY_MS = 500L
        private val CONFIRM_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
    }
}

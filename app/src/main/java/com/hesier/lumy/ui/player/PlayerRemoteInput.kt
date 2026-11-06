package com.hesier.lumy.ui.player

import android.view.KeyEvent
import android.view.View
import com.hesier.lumy.data.Settings
import com.hesier.lumy.databinding.ActivityPlayerBinding
import com.hesier.lumy.ui.player.PlayerControls.Focus as ControlsFocus

internal class PlayerRemoteInput(
    private val binding: ActivityPlayerBinding,
    private val controls: PlayerControls,
    private val seek: PlayerSeekController,
    private val episodes: PlayerEpisodeController,
    private val overlays: PlayerOverlayController,
    private val settings: Settings,
    private val holdSpeedGesture: HoldSpeedGesture,
    private val isReady: () -> Boolean,
    private val onTogglePlay: () -> Unit,
    private val onMore: () -> Unit,
) {
    fun handle(event: KeyEvent): Boolean {
        if (holdSpeedGesture.handle(event, enabled = isReady() &&
                binding.state.root.visibility != View.VISIBLE && !controls.visible && !overlays.hasFocus)) return true
        if (binding.state.root.visibility == View.VISIBLE || !isReady()) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down) onTogglePlay()
                return true
            }
            // The remote Menu key opens playback options directly.
            KeyEvent.KEYCODE_MENU -> {
                if (!down) onMore()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (down) seek.skip(settings.seekForwardMs)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (down) seek.skip(-settings.seekBackMs)
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (controls.visible) {
                    if (!down) controls.hide()
                    return true
                }
                if (overlays.upNextVisible) {
                    if (!down) overlays.dismissUpNext()
                    return true
                }
                if (controls.guardBackAfterAutoHide(down)) return true
            }
        }
        if (!controls.visible) {
            // Leave unhandled keys to the platform, including Back for exiting.
            if (event.keyCode !in HIDDEN_CONTROL_KEYS) return false
            // Let focused prompts receive confirmation and horizontal navigation.
            val confirm = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
            val horizontal = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            if ((overlays.hasFocus && confirm) || (overlays.upNextHasFocus && horizontal)) {
                return false
            }
            if (down) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> onTogglePlay()
                    KeyEvent.KEYCODE_DPAD_LEFT -> seek.skip(-settings.seekBackMs)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> seek.skip(settings.seekForwardMs)
                    // Down opens the frequently used action row; Up opens the seek bar.
                    KeyEvent.KEYCODE_DPAD_DOWN -> controls.show(ControlsFocus.BUTTONS)
                    else -> controls.show(ControlsFocus.SEEK)
                }
            }
            return true
        }
        controls.scheduleHide()
        if (episodes.handleKey(event)) return true
        if (binding.seek.hasFocus()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down) seek.scrub(event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT, event.repeatCount)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (down) if (seek.scrubTarget != null) seek.commit() else onTogglePlay()
                    return true
                }
            }
        }
        return false
    }

    private companion object {
        private val HIDDEN_CONTROL_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        )

    }
}

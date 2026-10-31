package com.hesier.lumy.ui.player

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.media3.exoplayer.ExoPlayer
import com.hesier.lumy.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class HoldSpeedTest {
    fun runAll(context: Context) = runBlocking {
        checkSettings(context)
        withContext(Dispatchers.Main) {
            val fixture = Fixture(context, this)
            try {
                fixture.checkTapAndHold()
                fixture.checkCancellation()
                fixture.checkOtherKeys()
            } finally {
                fixture.gesture.cancel()
                fixture.player.release()
            }
        }
    }

    private class Fixture(context: Context, scope: CoroutineScope) {
        val player = ExoPlayer.Builder(context).build()
        private var taps = 0
        private var holds = 0
        private var releases = 0
        private val originalSpeed = 1.25f
        val gesture = HoldSpeedGesture(
            scope = scope,
            onTap = { taps++ },
            onHold = { holds++; player.setPlaybackSpeed(3f) },
            onRelease = { releases++; player.setPlaybackSpeed(originalSpeed) },
        )

        init { player.setPlaybackSpeed(originalSpeed) }

        private fun key(action: Int, code: Int = KeyEvent.KEYCODE_DPAD_CENTER, repeat: Int = 0,
                        enabled: Boolean = true, flags: Int = 0) = gesture.handle(
            KeyEvent(0, 0, action, code, repeat, 0, -1, 0, flags), enabled,
        )

        suspend fun checkTapAndHold() {
            check(key(KeyEvent.ACTION_DOWN))
            check(key(KeyEvent.ACTION_UP))
            check(taps == 1 && holds == 0)
            delay(HoldSpeedGesture.HOLD_DELAY_MS + 100)
            check(holds == 0)
            check(key(KeyEvent.ACTION_DOWN))
            delay(HoldSpeedGesture.HOLD_DELAY_MS + 100)
            check(holds == 1 && player.playbackParameters.speed == 3f)
            check(key(KeyEvent.ACTION_DOWN, repeat = 1))
            check(key(KeyEvent.ACTION_DOWN, repeat = 2))
            check(holds == 1 && taps == 1)
            check(key(KeyEvent.ACTION_UP, enabled = false))
            check(taps == 1 && releases == 1 && player.playbackParameters.speed == originalSpeed)
        }

        suspend fun checkCancellation() {
            check(key(KeyEvent.ACTION_DOWN))
            gesture.cancel()
            delay(HoldSpeedGesture.HOLD_DELAY_MS + 100)
            check(holds == 1)
            check(key(KeyEvent.ACTION_UP))
            check(taps == 1)
            check(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            delay(HoldSpeedGesture.HOLD_DELAY_MS + 100)
            gesture.cancel()
            check(releases == 2 && player.playbackParameters.speed == originalSpeed)
            check(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            check(taps == 1)
        }

        fun checkOtherKeys() {
            check(key(KeyEvent.ACTION_DOWN))
            check(!key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            check(key(KeyEvent.ACTION_UP))
            check(taps == 1)
            check(!key(KeyEvent.ACTION_DOWN, enabled = false))
            check(!key(KeyEvent.ACTION_UP, enabled = false))
            check(!key(KeyEvent.ACTION_DOWN, repeat = 1))
            check(key(KeyEvent.ACTION_DOWN))
            check(key(KeyEvent.ACTION_UP, flags = KeyEvent.FLAG_CANCELED))
            check(taps == 1)
            // A lost key-up after focus loss must not swallow the next fresh press.
            check(key(KeyEvent.ACTION_DOWN))
            gesture.cancel()
            check(key(KeyEvent.ACTION_DOWN))
            check(key(KeyEvent.ACTION_UP))
            check(taps == 2)
        }
    }

    private fun checkSettings(context: Context) {
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("hold-speed-test-$name", mode)
        }
        val prefs = isolated.getSharedPreferences("settings", Context.MODE_PRIVATE)
        check(prefs.edit().clear().commit())
        try {
            val settings = Settings(isolated)
            check(settings.holdSpeed == 2f)
            for (rate in Settings.HOLD_SPEEDS) {
                settings.holdSpeed = rate
                check(Settings(isolated).holdSpeed == rate)
            }
            settings.holdSpeed = -1f
            check(Settings(isolated).holdSpeed == 2f)
        } finally {
            check(prefs.edit().clear().commit())
        }
    }
}

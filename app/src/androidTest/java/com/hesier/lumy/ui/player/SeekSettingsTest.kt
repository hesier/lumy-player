package com.hesier.lumy.ui.player

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.hesier.lumy.data.Settings
import okhttp3.OkHttpClient

class SeekSettingsTest {
    fun runAll(context: Context) {
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("seek-settings-test-$name", mode)
        }
        val prefs = isolated.getSharedPreferences("settings", Context.MODE_PRIVATE)
        check(prefs.edit().clear().commit())
        val settings = Settings(isolated)
        check(settings.seekForwardMs == 30_000L && settings.seekBackMs == 10_000L)
        for (seconds in Settings.SEEK_INTERVALS) {
            settings.seekForwardSeconds = seconds
            val saved = Settings(isolated)
            check(saved.seekForwardMs == seconds * 1_000L && saved.seekBackMs == 10_000L)
        }
        for (seconds in Settings.SEEK_INTERVALS) {
            settings.seekBackSeconds = seconds
            val saved = Settings(isolated)
            check(saved.seekBackMs == seconds * 1_000L && saved.seekForwardMs == 120_000L)
        }
        prefs.edit().putInt("seek_forward_seconds", -1).putInt("seek_back_seconds", 0).commit()
        check(settings.seekForwardMs == 30_000L && settings.seekBackMs == 10_000L)
        settings.seekForwardSeconds = 5
        settings.seekBackSeconds = 15
        val bundle = PlayerFactory.create(isolated, OkHttpClient(), emptyMap(), SyncOffsets(),
            seekBackMs = settings.seekBackMs, seekForwardMs = settings.seekForwardMs)
        try {
            check(bundle.player.seekForwardIncrement == 5_000L)
            check(bundle.player.seekBackIncrement == 15_000L)
        } finally {
            bundle.player.release()
            bundle.ass.release()
            bundle.secondaryAss.release()
        }
    }
}

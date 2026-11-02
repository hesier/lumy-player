package com.hesier.lumy.data

import android.content.Context
import android.content.SharedPreferences
import com.hesier.lumy.data.backup.BackupSettings
import org.json.JSONObject

class DanmakuSourcesPreferencesTest {
    private lateinit var prefs: SharedPreferences
    private val settings get() = DanmakuSourceSettings(prefs)

    fun runAll(context: Context) {
        prefs = context.getSharedPreferences("danmaku-source-preferences-test", Context.MODE_PRIVATE)
        try {
            check(prefs.edit().clear().commit())
            defaultsAndRoundTrip()
            backupValidation()
            invalidStoredValues()
            resetKeepsSource()
        } finally { check(prefs.edit().clear().commit()) }
    }

    private fun defaultsAndRoundTrip() {
        check(DanmakuOnlineSource.OFFICIAL == settings.onlineSource)
        check("" == settings.customUrl)
        val address = "https://example.test/proxy/token-value"
        settings.customUrl = "  $address  "
        settings.onlineSource = DanmakuOnlineSource.CUSTOM
        check(settings.customUrl.startsWith(address))
        check(DanmakuOnlineSource.CUSTOM == settings.onlineSource)
        check(DanmakuSourceSettings.KEYS == prefs.all.keys)
        val saved = settings.customUrl
        invalidUrls().forEach { url ->
            check(runCatching { settings.customUrl = url }.isFailure)
            check(saved == settings.customUrl)
        }
        settings.customUrl = ""
        check("" == settings.customUrl)
        settings.customUrl = address
    }

    private fun backupValidation() {
        check(BackupSettings.globalKeys.containsAll(DanmakuSourceSettings.KEYS))
        val json = JSONObject().put(DanmakuSourceSettings.SOURCE_KEY, "CUSTOM")
            .put(DanmakuSourceSettings.URL_KEY, settings.customUrl)
        val restored = BackupSettings.validate(json, false)
        check("CUSTOM" == restored[DanmakuSourceSettings.SOURCE_KEY])
        check(settings.customUrl == restored[DanmakuSourceSettings.URL_KEY])
        check("" == BackupSettings.read(DanmakuSourceSettings.URL_KEY, ""))
        listOf("UNKNOWN", 1, true, JSONObject.NULL).forEach {
            check(runCatching { BackupSettings.read(DanmakuSourceSettings.SOURCE_KEY, it) }.isFailure)
        }
        (invalidUrls() + listOf(1, true, JSONObject.NULL)).forEach {
            check(runCatching { BackupSettings.read(DanmakuSourceSettings.URL_KEY, it) }.isFailure)
        }
    }

    private fun invalidStoredValues() {
        prefs.edit().putInt(DanmakuSourceSettings.SOURCE_KEY, 1)
            .putBoolean(DanmakuSourceSettings.URL_KEY, true).commit()
        check(DanmakuOnlineSource.OFFICIAL == settings.onlineSource)
        check("" == settings.customUrl)
        prefs.edit().putString(DanmakuSourceSettings.SOURCE_KEY, "UNKNOWN")
            .putString(DanmakuSourceSettings.URL_KEY, "https://example.test/?token=private").commit()
        check(DanmakuOnlineSource.OFFICIAL == settings.onlineSource)
        check("" == settings.customUrl)
    }

    private fun resetKeepsSource() {
        settings.customUrl = "http://example.test:8765/proxy/token-value"
        settings.onlineSource = DanmakuOnlineSource.CUSTOM
        val expected = settings.customUrl
        DanmakuSettings(prefs).reset()
        check(expected == settings.customUrl)
        check(DanmakuOnlineSource.CUSTOM == settings.onlineSource)
    }

    private fun invalidUrls() = listOf("ftp://example.test", "https://user:secret@example.test",
        "https://example.test/path?token=private", "https://example.test/path#fragment", "not a url")
}

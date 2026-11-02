package com.hesier.lumy.data

import android.content.SharedPreferences
import com.hesier.lumy.net.danmaku.DanmakuEndpoint

enum class DanmakuOnlineSource { OFFICIAL, CUSTOM }

/** 来源偏好独立于显示样式，恢复显示默认值时保留。 */
class DanmakuSourceSettings(private val prefs: SharedPreferences) {
    var onlineSource: DanmakuOnlineSource
        get() = (prefs.all[SOURCE_KEY] as? String)?.let {
            runCatching { DanmakuOnlineSource.valueOf(it) }.getOrNull()
        } ?: DanmakuOnlineSource.OFFICIAL
        set(value) { prefs.edit().putString(SOURCE_KEY, value.name).apply() }

    var customUrl: String
        get() = (prefs.all[URL_KEY] as? String)?.let {
            runCatching { normalizeUrl(it) }.getOrNull()
        }.orEmpty()
        set(value) { prefs.edit().putString(URL_KEY, normalizeUrl(value)).apply() }

    companion object {
        const val SOURCE_KEY = "danmaku_source_online"
        const val URL_KEY = "danmaku_source_custom_url"
        val KEYS: Set<String> = setOf(SOURCE_KEY, URL_KEY)

        fun normalizeUrl(value: String): String {
            val trimmed = value.trim()
            require(trimmed.length <= 8192) { "Invalid danmaku URL" }
            return if (trimmed.isEmpty()) "" else DanmakuEndpoint(trimmed).baseUrl
        }
    }
}

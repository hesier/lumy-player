package com.hesier.lumy.data

import android.content.Context
import com.hesier.lumy.core.SubtitleRule

/**
 * 手动选过的音轨 / 字幕，按服务器（[SavedServer.id]）分开记，不同服务器的 id 可能重复。
 * - 文件：记下具体选的轨道 id。不依赖服务端保存，Jellyfin / Emby 不会记住选择
 * - 剧集：记下最后选的语言，同一部剧的其他集按这个语言去匹配
 */
class TrackMemory(context: Context) {
    private val prefs = context.getSharedPreferences("track_memory", Context.MODE_PRIVATE)

    init { migrateLegacyFiles() }

    fun fileAudio(serverId: String, partId: String): String? = prefs.getString("$serverId|part:$partId:audio", null)

    /** Null means no memory; an empty value remembers subtitles being turned off. */
    fun fileSubtitle(serverId: String, partId: String): String? = prefs.getString("$serverId|part:$partId:sub", null)

    private fun migrateLegacyFiles() {
        val legacy = prefs.all.filterKeys { "|part:" in it && !it.endsWith(":audio") && !it.endsWith(":sub") }
        if (legacy.isEmpty()) return
        val edit = prefs.edit()
        legacy.forEach { (key, value) ->
            val parts = (value as? String ?: return@forEach).split('|', limit = 2)
            if (!prefs.contains("$key:audio") && parts[0].isNotEmpty()) edit.putString("$key:audio", parts[0])
            if (!prefs.contains("$key:sub")) edit.putString("$key:sub", parts.getOrElse(1) { "" })
            edit.remove(key)
        }
        edit.apply()
    }

    fun showAudio(serverId: String, showId: String): String? = prefs.getString("$serverId|show:$showId:audio", null)

    fun showSubtitle(serverId: String, showId: String): SubtitleRule? =
        decodeSubtitleRule(prefs.getString("$serverId|show:$showId:sub", null))

    fun rememberFileAudio(serverId: String, partId: String, audioId: String) =
        prefs.edit().putString("$serverId|part:$partId:audio", audioId).apply()

    fun rememberFileSubtitle(serverId: String, partId: String, subtitleId: String?) =
        prefs.edit().putString("$serverId|part:$partId:sub", subtitleId.orEmpty()).apply()

    fun rememberShowAudio(serverId: String, showId: String, lang: String) {
        prefs.edit().putString("$serverId|show:$showId:audio", lang).apply()
    }

    fun rememberShowSubtitle(serverId: String, showId: String, rule: SubtitleRule) {
        prefs.edit().putString("$serverId|show:$showId:sub", encodeSubtitleRule(rule)).apply()
    }

    /** 删除服务器时一起清掉它的记录 */
    fun forgetServer(serverId: String) {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("$serverId|") }.forEach(edit::remove)
        edit.apply()
    }

    fun clearAudio() = clearType(":audio")

    fun clearSubtitles() = clearType(":sub")

    private fun clearType(suffix: String) {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.endsWith(suffix) }.forEach(edit::remove)
        edit.apply()
    }
}

internal fun encodeSubtitleRule(rule: SubtitleRule): String = when (rule) {
    SubtitleRule.Off -> "off"
    is SubtitleRule.ForcedOnly -> "forced:${rule.lang.orEmpty()}"
    is SubtitleRule.Language -> "lang:${rule.lang}"
}

internal fun decodeSubtitleRule(value: String?): SubtitleRule? = when {
    value == null -> null
    value == "off" -> SubtitleRule.Off
    value.startsWith("forced:") -> SubtitleRule.ForcedOnly(value.removePrefix("forced:").ifEmpty { null })
    value.startsWith("lang:") -> SubtitleRule.Language(value.removePrefix("lang:"))
    else -> null
}

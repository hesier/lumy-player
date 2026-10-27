package com.hesier.lumy.data

import android.content.Context
import com.hesier.lumy.core.SubtitleRule

/** 某个文件手动选的轨道；[subtitleId] 为 null 表示关闭字幕 */
data class ManualTracks(val audioId: String?, val subtitleId: String?)

/**
 * 手动选过的音轨 / 字幕，按服务器（[SavedServer.id]）分开记，不同服务器的 id 可能重复。
 * - 文件：记下具体选的轨道 id。不依赖服务端保存，Jellyfin / Emby 不会记住选择
 * - 剧集：记下最后选的语言，同一部剧的其他集按这个语言去匹配
 */
class TrackMemory(context: Context) {
    private val prefs = context.getSharedPreferences("track_memory", Context.MODE_PRIVATE)

    fun file(serverId: String, partId: String): ManualTracks? {
        val value = prefs.getString("$serverId|part:$partId", null) ?: return null
        val (audio, subtitle) = value.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return ManualTracks(audio.ifEmpty { null }, subtitle.ifEmpty { null })
    }

    fun showAudio(serverId: String, showId: String): String? = prefs.getString("$serverId|show:$showId:audio", null)

    fun showSubtitle(serverId: String, showId: String): SubtitleRule? =
        decodeSubtitleRule(prefs.getString("$serverId|show:$showId:sub", null))

    fun rememberFile(serverId: String, partId: String, tracks: ManualTracks) {
        prefs.edit().putString("$serverId|part:$partId", "${tracks.audioId.orEmpty()}|${tracks.subtitleId.orEmpty()}").apply()
    }

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

    fun clear() = prefs.edit().clear().apply()
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

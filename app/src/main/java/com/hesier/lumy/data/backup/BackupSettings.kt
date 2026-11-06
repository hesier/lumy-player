package com.hesier.lumy.data.backup

import com.hesier.lumy.data.DanmakuOnlineSource
import com.hesier.lumy.data.DanmakuSourceSettings
import com.hesier.lumy.data.DanmakuDensity
import com.hesier.lumy.data.DanmakuSettings
import com.hesier.lumy.data.DanmakuSize
import com.hesier.lumy.data.DanmakuSpeed
import com.hesier.lumy.data.DanmakuStroke
import com.hesier.lumy.data.Settings
import org.json.JSONArray
import org.json.JSONObject

internal object BackupSettings {
    val deviceKeys = setOf("passthrough", "display_supports_hdr", "match_frame_rate",
        "allow_software_video_decoding", "audio_delay_ms", "ui_scale")
    val serverKeys = setOf("hidden_sidebar", "sidebar_order", "allow_transcode", "bandwidth_mbps")
    private val booleans = setOf("passthrough", "show_remaining_time", "subtitle_background",
        "secondary_subtitle_background", "allow_transcode", "allow_software_video_decoding",
        "display_supports_hdr", "allow_subtitle_burn", "detail_show_cast", "detail_show_similar",
        "detail_show_file_info", "match_frame_rate", "auto_play_next", "remember_audio_selection",
        "remember_subtitle_selection", "auto_check_updates", "use_update_mirror", "danmaku_enabled",
        "danmaku_rolling", "danmaku_top", "danmaku_bottom", "danmaku_auto_reduce", "watch_next_enabled")
    private val enums = mapOf(
        "video_scale" to setOf("FIT", "ZOOM", "FILL"),
        "theme" to setOf("DARK", "LIGHT", "SYSTEM"),
        "language" to setOf("SYSTEM", "ENGLISH", "SIMPLIFIED_CHINESE", "TRADITIONAL_CHINESE"),
        "subtitle_mode" to setOf("SERVER", "LANGUAGE", "FORCED_ONLY", "OFF"),
        "skip_intro" to setOf("OFF", "BUTTON", "AUTO"), "skip_credits" to setOf("OFF", "BUTTON", "AUTO"),
        DanmakuSourceSettings.SOURCE_KEY to DanmakuOnlineSource.entries.map { it.name }.toSet(),
        "danmaku_density" to DanmakuDensity.entries.map { it.name }.toSet(),
        "danmaku_size" to DanmakuSize.entries.map { it.name }.toSet(),
        "danmaku_speed" to DanmakuSpeed.entries.map { it.name }.toSet(),
        "danmaku_stroke" to DanmakuStroke.entries.map { it.name }.toSet(),
    )
    private val integers = setOf("default_quality", "seek_forward_seconds", "seek_back_seconds",
        "subtitle_position", "secondary_subtitle_color", "bandwidth_mbps", "danmaku_area_percent", "danmaku_opacity_percent")
    private val floats = setOf("hold_speed", "subtitle_scale", "secondary_subtitle_scale", "ui_scale")
    private val strings = setOf("audio_language", "subtitle_language", "update_mirror_url", "sidebar_order")
    val globalKeys = booleans + enums.keys + integers + floats + strings +
        setOf("audio_delay_ms", "danmaku_offset_ms") + DanmakuSourceSettings.KEYS - serverKeys

    fun read(key: String, value: Any): Any {
        val valid = when (key) {
            in booleans -> value is Boolean
            in enums -> value is String && value in enums.getValue(key)
            in integers -> isInteger(value) && validInteger(key, (value as Number).toLong())
            in floats -> value is Number && validFloat(key, value.toDouble())
            "audio_delay_ms" -> isInteger(value) && (value as Number).toLong() in -86_400_000L..86_400_000L
            "danmaku_offset_ms" -> isInteger(value) && (value as Number).toLong().let {
                it in DanmakuSettings.OFFSET_RANGE && it % DanmakuSettings.OFFSET_STEP_MS == 0L
            }
            "hidden_sidebar" -> value is JSONArray && value.length() <= 1000 &&
                (0 until value.length()).all { value.get(it) is String && value.getString(it).length <= 1024 }
            DanmakuSourceSettings.URL_KEY -> value is String &&
                runCatching { DanmakuSourceSettings.normalizeUrl(value) }.isSuccess
            in strings -> value is String && value.length <= 8192
            else -> false
        }
        require(valid) { "Invalid setting: $key" }
        return when (key) {
            DanmakuSourceSettings.URL_KEY -> DanmakuSourceSettings.normalizeUrl(value as String)
            in integers -> (value as Number).toInt()
            in floats -> (value as Number).toFloat()
            "audio_delay_ms", "danmaku_offset_ms" -> (value as Number).toLong()
            "hidden_sidebar" -> (value as JSONArray).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            else -> value
        }
    }

    private fun validInteger(key: String, value: Long): Boolean = when (key) {
        "default_quality" -> value in 0L..4L
        "seek_forward_seconds", "seek_back_seconds" -> value in Settings.SEEK_INTERVALS.map(Int::toLong)
        "subtitle_position" -> value in 0L..3L
        "bandwidth_mbps" -> value in Settings.BANDWIDTH_OPTIONS.map(Int::toLong)
        "danmaku_area_percent" -> value in DanmakuSettings.AREA_OPTIONS.map(Int::toLong)
        "danmaku_opacity_percent" -> value in DanmakuSettings.OPACITY_OPTIONS.map(Int::toLong)
        else -> value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
    }

    private fun validFloat(key: String, value: Double): Boolean = value.isFinite() && when (key) {
        "hold_speed" -> value.toFloat() in Settings.HOLD_SPEEDS
        "ui_scale" -> value.toFloat() in Settings.UI_SCALES
        else -> value in 0.1..5.0
    }

    fun isInteger(value: Any) = value is Int || value is Long
    fun jsonValue(value: Any): Any = if (value is Set<*>) JSONArray(value.toList()) else value
    fun validate(values: JSONObject, server: Boolean): Map<String, Any> = values.keys().asSequence().associateWith { key ->
        require(key in if (server) serverKeys else globalKeys) { "Unknown setting: $key" }
        read(key, values.get(key))
    }
}

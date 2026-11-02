package com.hesier.lumy.data

import android.content.SharedPreferences

enum class DanmakuDensity { LOW, NORMAL, HIGH }
enum class DanmakuSize { EXTRA_SMALL, SMALL, NORMAL, LARGE, EXTRA_LARGE }
enum class DanmakuSpeed { SLOW, NORMAL, FAST }
enum class DanmakuStroke { OFF, THIN, NORMAL, THICK }

/** 仅保存用户偏好；具体渲染参数由播放器接入时决定。 */
class DanmakuSettings(private val prefs: SharedPreferences) {
    var enabled: Boolean
        get() = boolean("danmaku_enabled", false)
        set(value) = prefs.edit().putBoolean("danmaku_enabled", value).apply()

    var density: DanmakuDensity
        get() = enum("danmaku_density", DanmakuDensity.LOW)
        set(value) = prefs.edit().putString("danmaku_density", value.name).apply()

    var areaPercent: Int
        get() = integer("danmaku_area_percent", 50, AREA_OPTIONS)
        set(value) {
            require(value in AREA_OPTIONS)
            prefs.edit().putInt("danmaku_area_percent", value).apply()
        }

    var size: DanmakuSize
        get() = enum("danmaku_size", DanmakuSize.NORMAL)
        set(value) = prefs.edit().putString("danmaku_size", value.name).apply()

    var opacityPercent: Int
        get() = integer("danmaku_opacity_percent", 80, OPACITY_OPTIONS)
        set(value) {
            require(value in OPACITY_OPTIONS)
            prefs.edit().putInt("danmaku_opacity_percent", value).apply()
        }

    var speed: DanmakuSpeed
        get() = enum("danmaku_speed", DanmakuSpeed.NORMAL)
        set(value) = prefs.edit().putString("danmaku_speed", value.name).apply()

    var stroke: DanmakuStroke
        get() = enum("danmaku_stroke", DanmakuStroke.THIN)
        set(value) = prefs.edit().putString("danmaku_stroke", value.name).apply()

    var rolling: Boolean
        get() = boolean("danmaku_rolling", true)
        set(value) = prefs.edit().putBoolean("danmaku_rolling", value).apply()

    var top: Boolean
        get() = boolean("danmaku_top", true)
        set(value) = prefs.edit().putBoolean("danmaku_top", value).apply()

    var bottom: Boolean
        get() = boolean("danmaku_bottom", false)
        set(value) = prefs.edit().putBoolean("danmaku_bottom", value).apply()

    /** 正值延后，负值提前；这里只保存偏移量。 */
    var offsetMs: Long
        get() = runCatching { prefs.getLong("danmaku_offset_ms", 0L) }.getOrDefault(0L)
            .takeIf { it in OFFSET_RANGE && it % OFFSET_STEP_MS == 0L } ?: 0L
        set(value) {
            require(value in OFFSET_RANGE && value % OFFSET_STEP_MS == 0L)
            prefs.edit().putLong("danmaku_offset_ms", value).apply()
        }

    var autoReduce: Boolean
        get() = boolean("danmaku_auto_reduce", true)
        set(value) = prefs.edit().putBoolean("danmaku_auto_reduce", value).apply()

    fun reset() {
        prefs.edit().apply { KEYS.forEach { remove(it) } }.apply()
    }

    private fun boolean(key: String, fallback: Boolean): Boolean =
        runCatching { prefs.getBoolean(key, fallback) }.getOrDefault(fallback)

    private fun integer(key: String, fallback: Int, options: List<Int>): Int =
        runCatching { prefs.getInt(key, fallback) }.getOrDefault(fallback)
            .takeIf { it in options } ?: fallback

    private inline fun <reified T : Enum<T>> enum(key: String, fallback: T): T =
        runCatching { prefs.getString(key, null)?.let { enumValueOf<T>(it) } ?: fallback }.getOrDefault(fallback)

    companion object {
        val AREA_OPTIONS = listOf(25, 50, 75, 100)
        val OPACITY_OPTIONS = (30..100 step 10).toList()
        val OFFSET_RANGE = -60_000L..60_000L
        const val OFFSET_STEP_MS = 500L
        val KEYS = setOf("danmaku_enabled", "danmaku_density", "danmaku_area_percent", "danmaku_size",
            "danmaku_opacity_percent", "danmaku_speed", "danmaku_stroke", "danmaku_rolling", "danmaku_top",
            "danmaku_bottom", "danmaku_offset_ms", "danmaku_auto_reduce")
    }
}

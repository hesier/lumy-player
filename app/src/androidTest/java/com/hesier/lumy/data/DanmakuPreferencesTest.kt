package com.hesier.lumy.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.hesier.lumy.data.backup.BackupSettings
import org.json.JSONObject

class DanmakuPreferencesTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private val settings get() = Settings(context).danmaku

    fun runAll(context: Context) {
        this.context = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("danmaku-preferences-test-$name", mode)
        }
        prefs = this.context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        try {
            clear(); defaults()
            clear(); optionsRoundTrip()
            clear(); invalidStoredValuesFallBack()
            clear(); invalidSettersPreserveValues()
            clear(); resetPreservesOtherPreferences()
            clear(); backupRoundTrip()
            rejectsInvalidBackups()
        } finally { clear() }
    }

    private fun clear() { check(prefs.edit().clear().commit()) }

    private fun values(value: DanmakuSettings): List<Any> = listOf(value.enabled, value.density,
        value.areaPercent, value.size, value.opacityPercent, value.speed, value.stroke,
        value.rolling, value.top, value.bottom, value.offsetMs, value.autoReduce)

    private fun defaults() {
        val expected = listOf(false, DanmakuDensity.LOW, 50, DanmakuSize.NORMAL, 80,
            DanmakuSpeed.NORMAL, DanmakuStroke.THIN, true, true, false, 0L, true)
        check(expected == values(settings))
    }

    private fun optionsRoundTrip() {
        val value = settings
        DanmakuDensity.entries.forEach { value.density = it; check(it == settings.density) }
        DanmakuSize.entries.forEach { value.size = it; check(it == settings.size) }
        DanmakuSpeed.entries.forEach { value.speed = it; check(it == settings.speed) }
        DanmakuStroke.entries.forEach { value.stroke = it; check(it == settings.stroke) }
        DanmakuSettings.AREA_OPTIONS.forEach { value.areaPercent = it; check(it == settings.areaPercent) }
        DanmakuSettings.OPACITY_OPTIONS.forEach { value.opacityPercent = it; check(it == settings.opacityPercent) }
        for (offset in DanmakuSettings.OFFSET_RANGE step DanmakuSettings.OFFSET_STEP_MS) {
            value.offsetMs = offset
            check(offset == settings.offsetMs)
        }
        listOf(false, true).forEach { flag ->
            value.enabled = flag; value.rolling = flag; value.top = flag; value.bottom = flag; value.autoReduce = flag
            check(listOf(flag, flag, flag, flag, flag) == settings.let {
                listOf(it.enabled, it.rolling, it.top, it.bottom, it.autoReduce)
            })
        }
        value.density = DanmakuDensity.HIGH
        value.autoReduce = false
        check(DanmakuDensity.HIGH == settings.density && !settings.autoReduce)
        value.autoReduce = true
        check(DanmakuDensity.HIGH == settings.density && settings.autoReduce)
        check(values(value) == values(settings))
        check(DanmakuSettings.KEYS == prefs.all.keys)
        check(DanmakuSettings.KEYS.all { it.startsWith("danmaku_") })
    }

    private fun invalidStoredValuesFallBack() {
        prefs.edit().putString("danmaku_density", "UNKNOWN").putString("danmaku_size", "normal")
            .putString("danmaku_speed", "UNKNOWN").putString("danmaku_stroke", "UNKNOWN")
            .putInt("danmaku_area_percent", 26).putInt("danmaku_opacity_percent", 35)
            .putLong("danmaku_offset_ms", 501L).commit()
        defaults()
        prefs.edit().apply { DanmakuSettings.KEYS.forEach { putString(it, "invalid") } }.commit()
        defaults()
        prefs.edit().apply { DanmakuSettings.KEYS.forEach { putInt(it, 7) } }.commit()
        defaults()
        listOf(Long.MIN_VALUE, -60_500L, 60_500L, Long.MAX_VALUE).forEach {
            prefs.edit().putLong("danmaku_offset_ms", it).commit()
            check(0L == settings.offsetMs)
        }
    }

    private fun invalidSettersPreserveValues() {
        val value = settings
        value.areaPercent = 75; value.opacityPercent = 90; value.offsetMs = -1_500L
        listOf(Int.MIN_VALUE, -1, 0, 26, 101, Int.MAX_VALUE).forEach {
            rejects { value.areaPercent = it }
            check(75 == settings.areaPercent)
        }
        listOf(Int.MIN_VALUE, 0, 20, 35, 101, Int.MAX_VALUE).forEach {
            rejects { value.opacityPercent = it }
            check(90 == settings.opacityPercent)
        }
        listOf(Long.MIN_VALUE, -60_500L, -1L, 1L, 60_500L, Long.MAX_VALUE).forEach {
            rejects { value.offsetMs = it }
            check(-1_500L == settings.offsetMs)
        }
    }

    private fun writeAllPreferences() {
        settings.apply {
            enabled = true; density = DanmakuDensity.HIGH; areaPercent = 75; size = DanmakuSize.EXTRA_LARGE
            opacityPercent = 90; speed = DanmakuSpeed.FAST; stroke = DanmakuStroke.THICK
            rolling = false; top = false; bottom = true; offsetMs = -1_500L; autoReduce = false
        }
    }

    private fun resetPreservesOtherPreferences() {
        Settings(context).apply { theme = ThemeMode.LIGHT; audioDelayMs = 500L; autoPlayNext = false }
        val other = prefs.all.toMap()
        writeAllPreferences()
        settings.reset()
        defaults()
        check(other == prefs.all)
        check(DanmakuSettings.KEYS.none { prefs.contains(it) })
    }

    private fun backupRoundTrip() {
        writeAllPreferences()
        val expected = prefs.all.filterKeys { it in DanmakuSettings.KEYS }
        val json = JSONObject().apply { expected.forEach { (key, value) -> put(key, value) } }
        check(DanmakuSettings.KEYS.all { it in BackupSettings.globalKeys })
        check(DanmakuSettings.KEYS.none { it in BackupSettings.serverKeys || it in BackupSettings.deviceKeys })
        check(expected == BackupSettings.validate(JSONObject(json.toString()), server = false))
        expected.forEach { (key, value) -> check(value == BackupSettings.read(key, value!!)) }
        check(500L == BackupSettings.read("danmaku_offset_ms", 500))
        check(-60_000L == BackupSettings.read("danmaku_offset_ms", -60_000L))
        check(60_000L == BackupSettings.read("danmaku_offset_ms", 60_000L))
        check(75 == BackupSettings.read("danmaku_area_percent", 75L))
        DanmakuSettings.AREA_OPTIONS.forEach { check(it == BackupSettings.read("danmaku_area_percent", it)) }
        DanmakuSettings.OPACITY_OPTIONS.forEach { check(it == BackupSettings.read("danmaku_opacity_percent", it)) }
        val enums = mapOf("danmaku_density" to DanmakuDensity.entries.map { it.name },
            "danmaku_size" to DanmakuSize.entries.map { it.name }, "danmaku_speed" to DanmakuSpeed.entries.map { it.name },
            "danmaku_stroke" to DanmakuStroke.entries.map { it.name })
        enums.forEach { (key, options) -> options.forEach { check(it == BackupSettings.read(key, it)) } }
    }

    private fun rejectsInvalidBackups() {
        val invalid = mapOf(
            "danmaku_enabled" to listOf("true", 1, JSONObject.NULL),
            "danmaku_density" to listOf("UNKNOWN", "low", 0),
            "danmaku_size" to listOf("UNKNOWN", "normal", 1),
            "danmaku_speed" to listOf("UNKNOWN", 1), "danmaku_stroke" to listOf("UNKNOWN", 1),
            "danmaku_area_percent" to listOf(0, 26, 101, 50.0, "50", Long.MAX_VALUE),
            "danmaku_opacity_percent" to listOf(20, 35, 101, 80.0, "80", Long.MIN_VALUE),
            "danmaku_offset_ms" to listOf(-60_500L, 60_500L, -1L, 1L, 500.0, "500", Long.MAX_VALUE, Long.MIN_VALUE),
            "danmaku_rolling" to listOf("true"), "danmaku_top" to listOf(1),
            "danmaku_bottom" to listOf("false"), "danmaku_auto_reduce" to listOf(0),
        )
        invalid.forEach { (key, candidates) -> candidates.forEach { value ->
            rejects { BackupSettings.read(key, value) }
            rejects { BackupSettings.validate(JSONObject().put(key, value), server = false) }
        } }
        rejects { BackupSettings.validate(JSONObject().put("danmaku_unknown", true), server = false) }
        rejects { BackupSettings.validate(JSONObject().put("danmaku_enabled", true), server = true) }
    }

    private fun rejects(action: () -> Unit) {
        check(runCatching(action).exceptionOrNull() is IllegalArgumentException)
    }
}

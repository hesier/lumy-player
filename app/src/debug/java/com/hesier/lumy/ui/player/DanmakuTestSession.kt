package com.hesier.lumy.ui.player

import android.content.SharedPreferences
import android.os.SystemClock
import android.view.View
import com.hesier.lumy.data.DanmakuSettings

/** 合并偏好通知和持续压力采样，不写入任何用户偏好。 */
internal class DanmakuTestSession(
    private val preferences: SharedPreferences,
    private val host: View,
    changed: () -> Unit,
) {
    var settings = DanmakuTestPreferences.read(DanmakuSettings(preferences))
        private set
    private val adaptive = DanmakuAutoDensity()
    private var lastFrames = 0L
    val divisor: Int get() = adaptive.divisor
    private val refresh = Runnable {
        val next = DanmakuTestPreferences.read(DanmakuSettings(preferences))
        if (next != settings) {
            settings = next
            resetDensity()
            changed()
        }
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in DanmakuSettings.KEYS) {
            host.removeCallbacks(refresh)
            host.post(refresh)
        }
    }

    init { preferences.registerOnSharedPreferenceChangeListener(listener) }

    fun resetDensity() {
        adaptive.reset()
        lastFrames = 0
    }

    fun sample(metrics: DanmakuMetrics, playing: Boolean, nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        if (!settings.autoReduce) return false
        val frames = metrics.draw.count
        val newFrames = frames - lastFrames
        lastFrames = frames
        if (!playing || newFrames < MIN_FRAMES) { adaptive.idle(); return false }
        val refreshRate = host.display?.refreshRate?.takeIf { it.isFinite() && it > 0 } ?: DEFAULT_REFRESH_HZ
        val budget = NANOS_PER_SECOND / refreshRate
        val pressured = metrics.update.p95Nanos() + metrics.draw.p95Nanos() > budget * WORK_BUDGET ||
            metrics.intervals.p95Nanos() > budget * INTERVAL_BUDGET
        return adaptive.sample(pressured, nowMs)
    }

    fun release() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
        host.removeCallbacks(refresh)
    }

    private companion object {
        const val MIN_FRAMES = 1
        const val DEFAULT_REFRESH_HZ = 60f
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val WORK_BUDGET = 0.6
        const val INTERVAL_BUDGET = 1.5
    }
}

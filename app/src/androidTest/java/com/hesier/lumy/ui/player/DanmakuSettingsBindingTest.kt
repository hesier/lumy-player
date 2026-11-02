package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.content.SharedPreferences
import android.os.SystemClock
import com.hesier.lumy.data.DanmakuSettings
import kotlin.math.abs

/** 用隔离偏好验证播放中的设置绑定，不改用户设置。 */
internal class DanmakuSettingsBindingTest(
    private val instrumentation: Instrumentation,
    preferences: SharedPreferences,
) {
    private val settings = DanmakuSettings(preferences)
    private lateinit var probe: DanmakuProbe
    fun run(probe: DanmakuProbe, positionMs: () -> Long) {
        this.probe = probe
        main { probe.setProfile(1) }
        Thread.sleep(300)
        main { check(probe.appliedConfig == null && probe.visibleCount == 0) }
        main {
            settings.apply {
                enabled = true; density = com.hesier.lumy.data.DanmakuDensity.HIGH; areaPercent = 25
                size = com.hesier.lumy.data.DanmakuSize.EXTRA_SMALL; opacityPercent = 30
                speed = com.hesier.lumy.data.DanmakuSpeed.FAST; stroke = com.hesier.lumy.data.DanmakuStroke.OFF
                rolling = false; top = true; bottom = false; offsetMs = 1_500L; autoReduce = false
            }
        }
        await("settings enable and apply top comments") {
            probe.visibleComments.isNotEmpty() && probe.visibleComments.all { it.mode == 5 && it.textSize == 14 } &&
                probe.appliedConfig?.let { it.screenPart == 0.25f && it.alpha == 0.3f && it.rollingDurationMs == 6_000L } == true &&
                abs(probe.currentTimeMs - positionMs() + 1_500L) < 150
        }
        main {
            settings.apply {
                rolling = false; top = false; bottom = true; areaPercent = 75
                size = com.hesier.lumy.data.DanmakuSize.EXTRA_LARGE; opacityPercent = 100
                speed = com.hesier.lumy.data.DanmakuSpeed.SLOW; stroke = com.hesier.lumy.data.DanmakuStroke.THICK
                offsetMs = -500L
            }
        }
        await("bottom, font, speed and earlier offset") {
            probe.visibleComments.isNotEmpty() && probe.visibleComments.all { it.mode == 4 && it.textSize == 32 } &&
                probe.appliedConfig?.let { it.screenPart == 0.75f && it.alpha == 1f && it.rollingDurationMs == 10_000L } == true &&
                abs(probe.currentTimeMs - positionMs() - 500L) < 150
        }
        main { settings.bottom = false }
        await("all types off") { probe.appliedConfig == null && probe.visibleCount == 0 }
        main { settings.rolling = true }
        await("rolling only") { probe.visibleComments.isNotEmpty() && probe.visibleComments.all { it.mode == 1 } }
        main { settings.enabled = false }
        await("master off hides test") { probe.appliedConfig == null && probe.visibleCount == 0 }
        main { settings.reset(); settings.enabled = true }
        await("defaults restored live") { probe.visibleCount > 0 && probe.appliedPreferences.size == com.hesier.lumy.data.DanmakuSize.NORMAL }
    }


    private fun main(block: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        result!!.getOrThrow()
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var passed = false
            main { passed = condition() }
            if (passed) return
            Thread.sleep(40)
        }
        var diagnostic = ""
        main { diagnostic = "settings=${probe.appliedPreferences} config=${probe.appliedConfig?.let { "${it.screenPart}/${it.alpha}/${it.rollingDurationMs}" }} visible=${probe.visibleComments.map { "${it.mode}/${it.textSize}" }} clock=${probe.currentTimeMs}" }
        error("Timed out: $label $diagnostic")
    }
}

package com.hesier.lumy.ui.player

import android.app.Activity
import android.os.Build
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 按片源帧率切换显示器刷新率（24p 电影用 24Hz 输出），消除 3:2 下拉造成的画面抖动。
 * 只切换刷新率，不改分辨率；需要 Android 6.0+ 且显示设备支持多种模式。
 */
class RefreshRateMatcher(private val activity: Activity) {

    private var originalModeId = 0

    /** @return 是否发生了切换（切换期间 HDMI 可能黑屏一两秒） */
    fun match(frameRate: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || frameRate <= 0f) return false
        @Suppress("DEPRECATION")
        val display = activity.windowManager.defaultDisplay
        val current = display.mode
        val best = pickMode(display.supportedModes.toList(), current, frameRate) ?: return false
        if (best.modeId == current.modeId) return false
        if (originalModeId == 0) originalModeId = current.modeId
        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = best.modeId }
        return true
    }

    fun restore() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || originalModeId == 0) return
        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = 0 }
        originalModeId = 0
    }

    private fun pickMode(modes: List<Display.Mode>, current: Display.Mode, fps: Float): Display.Mode? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return modes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .mapNotNull { mode ->
                // 刷新率必须是帧率的整数倍（23.976 → 23.976/47.952，25 → 50）
                val ratio = mode.refreshRate / fps
                val multiple = ratio.roundToInt()
                val error = abs(ratio - multiple)
                if (multiple >= 1 && error < 0.002f) Triple(mode, multiple, error) else null
            }
            // 误差最小优先，其次倍数越小越好（24Hz 优于 48Hz）
            .minWithOrNull(compareBy<Triple<Display.Mode, Int, Float>> { it.third }.thenBy { it.second })
            ?.first
    }
}

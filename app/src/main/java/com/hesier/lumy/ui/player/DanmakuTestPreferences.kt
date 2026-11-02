package com.hesier.lumy.ui.player

import com.hesier.lumy.data.DanmakuDensity
import com.hesier.lumy.data.DanmakuSettings
import com.hesier.lumy.data.DanmakuSize
import com.hesier.lumy.data.DanmakuSpeed
import com.hesier.lumy.data.DanmakuStroke
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItemData

/** 测试适配参数独立于将来的弹幕来源 API。 */
internal data class DanmakuTestPreferences(
    val enabled: Boolean,
    val density: DanmakuDensity,
    val areaPercent: Int,
    val size: DanmakuSize,
    val opacityPercent: Int,
    val speed: DanmakuSpeed,
    val stroke: DanmakuStroke,
    val modes: List<Int>,
    val offsetMs: Long,
    val autoReduce: Boolean,
) {
    val admissionLimit: Int get() = when (density) {
        DanmakuDensity.LOW -> 1
        DanmakuDensity.NORMAL -> 2
        DanmakuDensity.HIGH -> 4
    }
    val textSizeSp: Int get() = when (size) {
        DanmakuSize.EXTRA_SMALL -> 14
        DanmakuSize.SMALL -> 18
        DanmakuSize.NORMAL -> 22
        DanmakuSize.LARGE -> 26
        DanmakuSize.EXTRA_LARGE -> 32
    }
    val rollingDurationMs: Long get() = when (speed) {
        DanmakuSpeed.SLOW -> 10_000L
        DanmakuSpeed.NORMAL -> 8_000L
        DanmakuSpeed.FAST -> 6_000L
    }
    val strokeDp: Float get() = when (stroke) {
        DanmakuStroke.OFF -> 0f
        DanmakuStroke.THIN -> 0.5f
        DanmakuStroke.NORMAL -> 1f
        DanmakuStroke.THICK -> 1.75f
    }

    fun config(filter: DanmakuDensityFilter) = DanmakuConfig(
        screenPart = areaPercent / 100f, alpha = opacityPercent / 100f, bold = false,
        durationMs = FIXED_DURATION_MS, rollingDurationMs = rollingDurationMs,
        allowOverlap = false, dataFilter = listOf(filter),
    )

    companion object {
        // 覆盖最慢滚动和最长偏移，提前准备的窗口不会因偏移变化而缺数据。
        const val FIXED_DURATION_MS = 4_000L
        fun read(settings: DanmakuSettings) = DanmakuTestPreferences(
            enabled = settings.enabled, density = settings.density, areaPercent = settings.areaPercent,
            size = settings.size, opacityPercent = settings.opacityPercent, speed = settings.speed, stroke = settings.stroke,
            modes = buildList {
                if (settings.rolling) add(DanmakuItemData.DANMAKU_MODE_ROLLING)
                if (settings.top) add(DanmakuItemData.DANMAKU_MODE_CENTER_TOP)
                if (settings.bottom) add(DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM)
            }, offsetMs = settings.offsetMs, autoReduce = settings.autoReduce,
        )
    }
}

package com.hesier.lumy.ui.player

import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.ecs.component.filter.DanmakuDataFilter
import com.kuaishou.akdanmaku.utils.DanmakuTimer

/** 每秒最多采样一次；只改变临时减量级别，不接触用户偏好。 */
internal class DanmakuAutoDensity {
    var divisor = 1
        private set
    private var pressuredCount = 0
    private var healthyCount = 0
    private var lastSampleMs: Long? = null
    private var lastAdjustmentMs: Long? = null

    fun sample(pressured: Boolean, nowMs: Long): Boolean {
        val previous = lastSampleMs
        if (previous != null && nowMs - previous < SAMPLE_INTERVAL_MS) return false
        lastSampleMs = nowMs
        pressuredCount = if (pressured) (pressuredCount + 1).coerceAtMost(PRESSURED_SAMPLES) else 0
        healthyCount = if (!pressured) (healthyCount + 1).coerceAtMost(HEALTHY_SAMPLES) else 0
        val adjustedAt = lastAdjustmentMs
        if (adjustedAt != null && nowMs - adjustedAt < COOLDOWN_MS) return false
        val next = when {
            pressuredCount >= PRESSURED_SAMPLES && divisor < MAX_DIVISOR -> divisor * 2
            healthyCount >= HEALTHY_SAMPLES && divisor > 1 -> divisor / 2
            else -> return false
        }
        divisor = next
        lastAdjustmentMs = nowMs
        pressuredCount = 0
        healthyCount = 0
        return true
    }

    /** 暂停、缓冲等不可采样时清连续计数，保留当前级别和调整冷却。 */
    fun idle() {
        pressuredCount = 0
        healthyCount = 0
        lastSampleMs = null
    }

    fun reset() {
        divisor = 1
        lastAdjustmentMs = null
        idle()
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 1_000L
        const val COOLDOWN_MS = 5_000L
        const val PRESSURED_SAMPLES = 3
        const val HEALTHY_SAMPLES = 20
        const val MAX_DIVISOR = 4
    }
}

/** 用稳定 ID 的混洗高位分桶，避免数量档位与类型轮转的低位规律互相影响。 */
internal class DanmakuDensityFilter : DanmakuDataFilter(1 shl 28) {
    @Volatile var divisor = 1
        set(value) {
            require(value == 1 || value == 2 || value == 4)
            field = value
        }

    override fun filter(item: DanmakuItem, timer: DanmakuTimer, config: DanmakuConfig): Boolean {
        val current = divisor
        if (current == 1) return false
        var hash = item.data.danmakuId
        hash = (hash xor (hash ushr 33)) * MIX_FIRST
        hash = (hash xor (hash ushr 33)) * MIX_SECOND
        hash = hash xor (hash ushr 33)
        return (hash ushr 62) >= 4 / current
    }

    private companion object {
        const val MIX_FIRST = -49_064_778_989_728_563L
        const val MIX_SECOND = -4_265_267_296_055_464_877L
    }
}

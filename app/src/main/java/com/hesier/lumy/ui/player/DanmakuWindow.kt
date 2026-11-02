package com.hesier.lumy.ui.player

import com.hesier.lumy.net.danmaku.DanmakuComment
import com.kuaishou.akdanmaku.data.DanmakuItemData
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 全集保留在后台；引擎只接收最多前 12 秒、后 60 秒的限流数据。 */
internal data class DanmakuWindow(
    val positionMs: Long,
    val items: List<DanmakuItemData>,
) {
    val endMs: Long get() = positionMs + FUTURE_MS

    companion object {
        const val PREROLL_MS = 12_000L
        const val FUTURE_MS = 60_000L
        private const val BUCKET_MS = 500L
        private const val MAX_CODEPOINTS = 80

        suspend fun sort(items: List<DanmakuComment>): List<DanmakuComment> {
            val context = currentCoroutineContext()
            return items.toMutableList().apply {
                sortWith { left, right ->
                    context.ensureActive()
                    left.timeMs.compareTo(right.timeMs)
                }
            }
        }

        /** 在完整时间线上准入一次，避免窗口起点改变滑动限流所选的 ID。 */
        suspend fun admit(all: List<DanmakuComment>, style: DanmakuTestPreferences): List<DanmakuComment> {
            val recent = java.util.ArrayDeque<Long>()
            val result = ArrayList<DanmakuComment>()
            for (item in all) {
                currentCoroutineContext().ensureActive()
                val mode = mode(item.mode) ?: continue
                if (mode !in style.modes || item.text.isBlank()) continue
                while (recent.isNotEmpty() && recent.first <= item.timeMs - BUCKET_MS) recent.removeFirst()
                if (recent.size >= style.admissionLimit) continue
                recent.addLast(item.timeMs)
                result.add(item)
            }
            return result
        }

        /** admitted 必须是相同 density/modes 下已排序且全局准入的数据。 */
        suspend fun prepare(admitted: List<DanmakuComment>, position: Long, style: DanmakuTestPreferences): DanmakuWindow {
            val start = (position - PREROLL_MS).coerceAtLeast(0)
            val end = position + FUTURE_MS
            val result = ArrayList<DanmakuItemData>()
            var index = lowerBound(admitted, start)
            while (index < admitted.size && admitted[index].timeMs <= end) {
                currentCoroutineContext().ensureActive()
                val item = admitted[index++]
                val mode = mode(item.mode) ?: continue
                val length = item.text.codePointCount(0, item.text.length).coerceAtMost(MAX_CODEPOINTS)
                val text = item.text.substring(0, item.text.offsetByCodePoints(0, length))
                result.add(DanmakuItemData(item.id, item.timeMs, text, mode, style.textSizeSp, item.color))
            }
            return DanmakuWindow(position, result)
        }

        private fun lowerBound(all: List<DanmakuComment>, start: Long): Int {
            var low = 0
            var high = all.size
            while (low < high) {
                val middle = low + (high - low) / 2
                if (all[middle].timeMs < start) low = middle + 1 else high = middle
            }
            return low
        }

        private fun mode(value: Int): Int? = when (value) {
            1 -> DanmakuItemData.DANMAKU_MODE_ROLLING
            4 -> DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM
            5 -> DanmakuItemData.DANMAKU_MODE_CENTER_TOP
            else -> null
        }
    }
}

package com.hesier.lumy.ui.player

import android.graphics.Color
import com.kuaishou.akdanmaku.data.DanmakuItemData

/** Bounds admitted data before it can create entities, text bitmaps or cache work. */
internal object DanmakuInput {
    const val MAX_TEXT_CODEPOINTS = 80
    const val BUCKET_MS = 500L
    const val FIXTURE_DURATION_MS = 300_000L
    const val PREROLL_MS = 12_000L

    fun fixture(request: Request): List<DanmakuItemData> {
        val interval = if (request.profile == 1) 100L else 25L
        if (request.preferences.modes.isEmpty()) return emptyList()
        val raw = (request.startMs..request.endMs step interval).mapIndexed { index, time ->
            DanmakuItemData(index.toLong(), time, request.texts[index % request.texts.size],
                request.preferences.modes.first(), request.preferences.textSizeSp, Color.WHITE)
        }
        return limit(raw, request).mapIndexed { index, item ->
            DanmakuItemData(item.danmakuId, item.position, item.content,
                request.preferences.modes[index % request.preferences.modes.size], item.textSize, item.textColor)
        }
    }

    fun limit(items: List<DanmakuItemData>, request: Request): List<DanmakuItemData> {
        val recent = java.util.ArrayDeque<Long>()
        val limit = request.preferences.admissionLimit
        return items.asSequence().filter { it.position in request.startMs..request.endMs && it.mode in request.preferences.modes }
            .sortedBy { it.position }.filter { item ->
                while (recent.isNotEmpty() && recent.peekFirst() <= item.position - BUCKET_MS) recent.removeFirst()
                if (recent.size >= limit) false else { recent.addLast(item.position); true }
            }.map { item ->
                val end = item.content.offsetByCodePoints(0,
                    item.content.codePointCount(0, item.content.length).coerceAtMost(MAX_TEXT_CODEPOINTS))
                DanmakuItemData(item.danmakuId, item.position, item.content.substring(0, end),
                    item.mode, item.textSize, item.textColor)
            }.toList()
    }

    data class Request(val profile: Int, val positionMs: Long, val texts: List<String>, val preferences: DanmakuTestPreferences) {
        val startMs: Long get() = (positionMs - PREROLL_MS).coerceAtLeast(0)
        val endMs: Long get() = positionMs + FIXTURE_DURATION_MS
    }
}

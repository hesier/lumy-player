package com.hesier.lumy.ui.player

import android.graphics.Color
import android.os.Message
import com.kuaishou.akdanmaku.LumyCacheBudget
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.data.ItemState

internal object DanmakuBudgetTest {
    fun runAll() {
        checkInput()
        checkQueue()
    }

    private fun checkInput() {
        val prefs = instrumentationPreferences()
        val request = DanmakuInput.Request(1, 1_000, listOf("sample"), prefs)
        val burst = List(100) { data(it.toLong(), "🙂".repeat(200)) }
        val normal = DanmakuInput.limit(burst, request)
        check(normal.size == request.preferences.admissionLimit)
        check(normal.first().content.codePointCount(0, normal.first().content.length) == DanmakuInput.MAX_TEXT_CODEPOINTS)
        check(normal.first().content.last().isLowSurrogate())
        val high = request.copy(profile = 2, preferences = prefs.copy(density = com.hesier.lumy.data.DanmakuDensity.HIGH))
        val stress = DanmakuInput.limit(burst, high)
        check(stress.size == high.preferences.admissionLimit)
        val boundary = listOf(data(1, "x", 499), data(2, "x", 501))
        check(DanmakuInput.limit(boundary, request).size == 1)
        check(DanmakuInput.limit(listOf(data(0, "x", -1)), request).isEmpty())
    }

    internal fun instrumentationPreferences() = DanmakuTestPreferences(
        enabled = true, density = com.hesier.lumy.data.DanmakuDensity.LOW, areaPercent = 50,
        size = com.hesier.lumy.data.DanmakuSize.NORMAL, opacityPercent = 80,
        speed = com.hesier.lumy.data.DanmakuSpeed.NORMAL, stroke = com.hesier.lumy.data.DanmakuStroke.THIN,
        modes = listOf(DanmakuItemData.DANMAKU_MODE_ROLLING, DanmakuItemData.DANMAKU_MODE_CENTER_TOP),
        offsetMs = 0, autoReduce = true,
    )

    private fun checkQueue() {
        val manager = Any()
        val items = List(65) { DanmakuItem(data(it.toLong(), "sample")) }
        var notifications = 0
        LumyCacheBudget.onReady(manager) { notifications++ }
        repeat(8) {
            LumyCacheBudget.beginBatch(manager)
            for (index in it * 8 until it * 8 + 8) {
                items[index].state = ItemState.Measuring
                check(LumyCacheBudget.admit(manager, items[index], 0))
            }
        }
        check(LumyCacheBudget.snapshot(manager)[0] == 64L)
        LumyCacheBudget.beginBatch(manager)
        items.last().state = ItemState.Measuring
        check(!LumyCacheBudget.admit(manager, items.last(), 0))
        check(items.last().state == ItemState.Uninitialized && notifications == 1)
        val message = Message.obtain().apply { what = 0 }
        LumyCacheBudget.taskStarted(manager, message)
        LumyCacheBudget.cancel(manager)
        check(items[0].state == ItemState.Measuring && items[1].state == ItemState.Uninitialized)
        LumyCacheBudget.taskFinished(manager)
        check(items[0].state == ItemState.Uninitialized)
        LumyCacheBudget.beginBatch(manager)
        items.last().state = ItemState.Rendering
        check(LumyCacheBudget.admit(manager, items.last(), 1))
        LumyCacheBudget.close(manager)
        check(items.last().state == ItemState.Measured && LumyCacheBudget.snapshot(manager)[0] == 0L)
        check(!LumyCacheBudget.admit(manager, items.last(), 1))
        message.recycle()
    }

    private fun data(id: Long, text: String, position: Long = 1_000) =
        DanmakuItemData(id, position, text, DanmakuItemData.DANMAKU_MODE_ROLLING, 25, Color.WHITE)
}

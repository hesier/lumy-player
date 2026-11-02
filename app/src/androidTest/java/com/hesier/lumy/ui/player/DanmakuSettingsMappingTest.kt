package com.hesier.lumy.ui.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.hesier.lumy.data.DanmakuDensity
import com.hesier.lumy.data.DanmakuSize
import com.hesier.lumy.data.DanmakuSpeed
import com.hesier.lumy.data.DanmakuStroke
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.ui.DanmakuDisplayer

internal object DanmakuSettingsMappingTest {
    private val base get() = DanmakuBudgetTest.instrumentationPreferences()
    private val display = object : DanmakuDisplayer {
        override var width = 1920
        override var height = 1080
        override var margin = 4
        override var allMarginTop = 0f
        override var density = 2f
        override var scaleDensity = 2f
        override var densityDpi = 320
    }

    fun runAll() {
        val config = base.config(DanmakuDensityFilter())
        check(config.screenPart == 0.5f && config.alpha == 0.8f && !config.allowOverlap)
        check(config.rollingDurationMs == 8_000L)
        check(DanmakuSpeed.entries.map { base.copy(speed = it).rollingDurationMs } == listOf(10_000L, 8_000L, 6_000L))
        check(DanmakuStroke.entries.map { base.copy(stroke = it).strokeDp } == listOf(0f, 0.5f, 1f, 1.75f))
        checkInputSettings()
        checkFontAndOutline()
    }

    private fun checkInputSettings() {
        for (profile in listOf(1, 2)) {
            var previous = 0
            for (density in DanmakuDensity.entries) {
                val settings = base.copy(density = density, modes = listOf(1, 5, 4))
                val request = DanmakuInput.Request(profile, 20_000, listOf("test"), settings)
                val data = DanmakuInput.fixture(request)
                check(data.size > previous)
                previous = data.size
                check(data.map { it.mode }.toSet() == settings.modes.toSet())
                check(data.all { it.textSize == settings.textSizeSp })
            }
        }
        val empty = base.copy(modes = emptyList())
        check(DanmakuInput.fixture(DanmakuInput.Request(1, 0, listOf("test"), empty)).isEmpty())
    }

    private fun checkFontAndOutline() {
        var previousWidth = 0
        for (size in DanmakuSize.entries) {
            val settings = base.copy(size = size)
            val renderer = DanmakuTextRenderer(settings.strokeDp)
            val item = item(settings.textSizeSp)
            val measured = renderer.measure(item, display, settings.config(DanmakuDensityFilter()))
            check(measured.width > previousWidth)
            previousWidth = measured.width
        }
        val off = render(DanmakuStroke.OFF)
        val thick = render(DanmakuStroke.THICK)
        try {
            check(thick.width > off.width && thick.height > off.height)
            check(blackPixels(off) == 0)
            check(blackPixels(thick) > 0)
        } finally { off.recycle(); thick.recycle() }
    }

    private fun render(stroke: DanmakuStroke): Bitmap {
        val settings = base.copy(stroke = stroke)
        val config = settings.config(DanmakuDensityFilter())
        val renderer = DanmakuTextRenderer(settings.strokeDp)
        val item = item(settings.textSizeSp)
        val size = renderer.measure(item, display, config)
        return Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).also {
            renderer.draw(item, Canvas(it), display, config)
        }
    }

    private fun item(size: Int) = DanmakuItem(DanmakuItemData(1, 1_000, "弹幕 Test", 1, size, Color.WHITE))
    private fun blackPixels(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { Color.alpha(it) > 0 && Color.red(it) == 0 && Color.green(it) == 0 && Color.blue(it) == 0 }
    }
}

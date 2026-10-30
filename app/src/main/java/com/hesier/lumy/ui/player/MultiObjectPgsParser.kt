@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.pgs.PgsParser
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** PGS composition state is separate from object data: a display set may reuse either. */
internal class MultiObjectPgsParser : SubtitleParser {
    private data class Placement(val id: Int, val window: Int, val x: Int, val y: Int, val crop: Rect?)
    private data class Composition(val width: Int, val height: Int, val palette: Int, val objects: List<Placement>)

    private val palettes = HashMap<Int, IntArray>()
    private val objects = PgsBitmapObjects()
    private val windows = HashMap<Int, Rect>()
    private var composition: Composition? = null
    private var pending = false
    private var invalid = false

    override fun getCueReplacementBehavior() = PgsParser.CUE_REPLACEMENT_BEHAVIOR

    override fun reset() {
        palettes.clear()
        objects.clear()
        windows.clear()
        composition = null
        pending = false
        invalid = false
    }

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>,
    ) = consume(data, offset, length, output)

    internal fun currentCues(): List<Cue> = if (invalid) emptyList() else buildCues()

    internal fun consume(
        data: ByteArray,
        offset: Int,
        length: Int,
        output: Consumer<CuesWithTiming>?,
    ) {
        if (offset < 0 || length < 0 || offset > data.size - length) return
        val input = if (length > 0 && data[offset].toInt() and 0xff == 0x78) {
            val inflated = inflate(data, offset, length) ?: run { invalid = true; return }
            PgsBytes(inflated)
        } else PgsBytes(data, offset, offset + length)
        while (input.remaining >= 3) {
            val type = input.u8()
            val size = input.u16()
            if (size > input.remaining) { invalid = true; return }
            val section = input.section(size)
            when (type) {
                0x14 -> readPalette(section)
                0x15 -> objects.read(section)
                0x16 -> readComposition(section)
                0x17 -> readWindows(section)
                0x80 -> {
                    if (pending) {
                        if (output != null) output.accept(CuesWithTiming(currentCues(), C.TIME_UNSET, C.TIME_UNSET))
                        pending = false
                    }
                }
            }
        }
        if (input.remaining != 0) invalid = true
    }

    private fun readComposition(input: PgsBytes) {
        pending = true
        invalid = false
        composition = null
        if (input.remaining < 11) { invalid = true; return }
        val width = input.u16()
        val height = input.u16()
        input.skip(3) // Frame rate and composition number do not affect placement.
        val state = input.u8()
        input.skip(1)
        val palette = input.u8()
        val count = input.u8()
        if (state and 0x80 != 0) {
            palettes.clear()
            objects.clear()
            windows.clear()
        }
        if (width == 0 || height == 0 || width.toLong() * height > PgsBitmapObjects.MAX_PIXELS) {
            invalid = true
            return
        }
        val placements = ArrayList<Placement>(count)
        repeat(count) {
            val placement = readPlacement(input)
            if (placement == null) { invalid = true; return }
            placements.add(placement)
        }
        if (input.remaining != 0) { invalid = true; return }
        composition = Composition(width, height, palette, placements)
    }

    private fun readPlacement(input: PgsBytes): Placement? {
        if (input.remaining < 8) return null
        val id = input.u16()
        val window = input.u8()
        val flags = input.u8()
        val x = input.u16()
        val y = input.u16()
        var crop: Rect? = null
        if (flags and 0x80 != 0) {
            if (input.remaining < 8) return null
            val left = input.u16()
            val top = input.u16()
            crop = Rect(left, top, left + input.u16(), top + input.u16())
            if (crop.isEmpty) return null
        }
        return Placement(id, window, x, y, crop)
    }

    private fun readWindows(input: PgsBytes) {
        if (input.remaining < 1) { invalid = true; return }
        val count = input.u8()
        if (input.remaining != count * 9) { invalid = true; return }
        repeat(count) {
            val id = input.u8()
            val x = input.u16()
            val y = input.u16()
            windows[id] = Rect(x, y, x + input.u16(), y + input.u16())
        }
    }

    private fun readPalette(input: PgsBytes) {
        if (input.remaining < 2 || input.remaining % 5 != 2) { invalid = true; return }
        val id = input.u8()
        input.skip(1)
        val palette = palettes.getOrPut(id) { IntArray(256) }
        while (input.remaining > 0) {
            val index = input.u8()
            val y = input.u8()
            val cr = input.u8() - 128
            val cb = input.u8() - 128
            val alpha = input.u8()
            val red = (y + 1.402 * cr).toInt().coerceIn(0, 255)
            val green = (y - 0.34414 * cb - 0.71414 * cr).toInt().coerceIn(0, 255)
            val blue = (y + 1.772 * cb).toInt().coerceIn(0, 255)
            palette[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }

    private fun buildCues(): List<Cue> {
        val frame = composition ?: return emptyList()
        if (frame.objects.isEmpty()) return emptyList()
        val palette = palettes[frame.palette] ?: return emptyList()
        val cues = ArrayList<Cue>(frame.objects.size)
        var pixels = 0L
        val pixelBudget = minOf(PgsBitmapObjects.MAX_PIXELS.toLong(), Runtime.getRuntime().maxMemory() / 32)
        for (placement in frame.objects) {
            val bitmap = objects.decode(placement.id, palette, pixelBudget - pixels) ?: return emptyList()
            pixels += bitmap.width.toLong() * bitmap.height
            if (pixels > PgsBitmapObjects.MAX_PIXELS) return emptyList()
            val crop = placement.crop
            if (crop != null && (crop.left < 0 || crop.top < 0 || crop.right > bitmap.width || crop.bottom > bitmap.height)) return emptyList()
            // A valid animation object can move outside its window while another remains visible.
            buildCue(frame, placement, bitmap)?.let(cues::add)
        }
        return cues
    }

    private fun buildCue(frame: Composition, placement: Placement, pixels: PgsBitmapObjects.Pixels): Cue? {
        val source = placement.crop ?: Rect(0, 0, pixels.width, pixels.height)
        if (source.left < 0 || source.top < 0 || source.right > pixels.width || source.bottom > pixels.height) return null
        val destination = Rect(placement.x, placement.y, placement.x + source.width(), placement.y + source.height())
        val visible = Rect(destination)
        if (!visible.intersect(0, 0, frame.width, frame.height)) return null
        windows[placement.window]?.let { if (!visible.intersect(it)) return null }
        val left = source.left + visible.left - destination.left
        val top = source.top + visible.top - destination.top
        val bitmap = Bitmap.createBitmap(
            pixels.colors, top * pixels.width + left, pixels.width,
            visible.width(), visible.height(), Bitmap.Config.ARGB_8888,
        )
        return Cue.Builder().setBitmap(bitmap)
            .setPosition(visible.left.toFloat() / frame.width).setPositionAnchor(Cue.ANCHOR_TYPE_START)
            .setLine(visible.top.toFloat() / frame.height, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START)
            .setSize(visible.width().toFloat() / frame.width)
            .setBitmapHeight(visible.height().toFloat() / frame.height).build()
    }

    private fun inflate(data: ByteArray, offset: Int, length: Int): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(data, offset, length)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 || output.size() + count > MAX_INFLATED_BYTES) return null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    companion object {
        private const val MAX_INFLATED_BYTES = 32 * 1024 * 1024
    }
}

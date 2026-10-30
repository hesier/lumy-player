package com.hesier.lumy.ui.player

import java.util.Arrays

/** Keeps encoded objects, including sequences split across subtitle samples. */
internal class PgsBitmapObjects {
    private data class ObjectData(
        val version: Int,
        val width: Int,
        val height: Int,
        val bytes: ByteArray,
        var position: Int = 0,
        var complete: Boolean = false,
    )

    data class Pixels(val width: Int, val height: Int, val colors: IntArray)

    private val objects = LinkedHashMap<Int, ObjectData>()
    private var allocated = 0

    fun clear() {
        objects.clear()
        allocated = 0
    }

    fun read(segment: PgsBytes) {
        if (segment.remaining < 4) return
        val id = segment.u16()
        val version = segment.u8()
        val flags = segment.u8()
        if (flags and 0x80 != 0) {
            remove(id)
            if (segment.remaining < 7) return
            val size = segment.u24() - 4
            val width = segment.u16()
            val height = segment.u16()
            if (size !in 1..MAX_OBJECT_BYTES || width == 0 || height == 0) return
            if (width.toLong() * height > MAX_PIXELS) return
            while (allocated + size > MAX_CACHE_BYTES || objects.size >= MAX_OBJECTS) {
                remove(objects.keys.first())
            }
            objects[id] = ObjectData(version, width, height, ByteArray(size))
            allocated += size
        }
        val value = objects[id] ?: return
        if (value.version != version || value.complete || segment.remaining > value.bytes.size - value.position) {
            remove(id)
            return
        }
        val count = segment.remaining
        segment.copyTo(value.bytes, value.position, count)
        value.position += count
        if (flags and 0x40 != 0) {
            if (value.position == value.bytes.size) value.complete = true else remove(id)
        }
    }

    fun decode(id: Int, palette: IntArray, pixelBudget: Long): Pixels? {
        val value = objects[id]?.takeIf { it.complete } ?: return null
        if (value.width.toLong() * value.height > pixelBudget) return null
        val input = PgsBytes(value.bytes)
        val pixels = IntArray(value.width * value.height)
        var x = 0
        var y = 0
        while (input.remaining > 0 && y < value.height) {
            val color = input.u8()
            if (color != 0) {
                if (x >= value.width) return null
                pixels[y * value.width + x++] = palette[color]
                continue
            }
            if (input.remaining == 0) return null
            val flags = input.u8()
            if (flags == 0) {
                // End-of-line also permits transparent padding to the declared width.
                x = 0
                y++
                continue
            }
            if (flags and 0x40 != 0 && input.remaining == 0) return null
            val run = if (flags and 0x40 == 0) flags and 0x3f
            else ((flags and 0x3f) shl 8) or input.u8()
            if (flags and 0x80 != 0 && input.remaining == 0) return null
            val argb = if (flags and 0x80 == 0) 0 else palette[input.u8()]
            if (run == 0 || run > value.width - x) return null
            Arrays.fill(pixels, y * value.width + x, y * value.width + x + run, argb)
            x += run
        }
        if (input.remaining != 0 || (y < value.height && (y != value.height - 1 || x != value.width))) return null
        return Pixels(value.width, value.height, pixels)
    }

    private fun remove(id: Int) {
        objects.remove(id)?.let { allocated -= it.bytes.size }
    }

    companion object {
        private const val MAX_OBJECT_BYTES = 8 * 1024 * 1024
        private const val MAX_CACHE_BYTES = 16 * 1024 * 1024
        private const val MAX_OBJECTS = 256
        const val MAX_PIXELS = 4096 * 2160
    }
}

/** All callers validate section lengths before reading its fields. */
internal class PgsBytes(
    private val data: ByteArray,
    var position: Int = 0,
    private val limit: Int = data.size,
) {
    val remaining get() = limit - position
    fun u8(): Int = data[position++].toInt() and 0xff
    fun u16(): Int = (u8() shl 8) or u8()
    fun u24(): Int = (u16() shl 8) or u8()
    fun skip(count: Int) { position += count }
    fun section(size: Int): PgsBytes = PgsBytes(data, position, position + size).also { position += size }
    fun copyTo(destination: ByteArray, offset: Int, count: Int) {
        data.copyInto(destination, offset, position, position + count)
        position += count
    }
}

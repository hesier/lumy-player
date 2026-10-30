@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.graphics.Rect
import androidx.media3.common.text.Cue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Private queue format containing compressed objects and all state needed by one display set. */
internal object PgsSnapshotCodec {
    const val MIME_TYPE = "text/x-lumy-pgs"
    const val MAX_SAMPLE_BYTES = PgsBitmapObjects.MAX_CACHE_BYTES + 32 * 1024
    const val MAX_DECODED_PIXELS = PgsBitmapObjects.MAX_PIXELS
    private const val MAGIC = 0x4c504753
    private const val VERSION = 1
    private const val PALETTE_SIZE = 256

    internal data class State(
        val composition: MultiObjectPgsParser.Composition,
        val palette: IntArray,
        val windows: Map<Int, Rect>,
        val objects: List<PgsBitmapObjects.Encoded>,
    )

    internal fun encode(state: State?): ByteArray {
        val frame = state?.takeIf { validPixelBudget(it) }
        val capacity = if (frame == null) 6 else 16 + PALETTE_SIZE * 4 + frame.windows.size * 17 +
            frame.composition.objects.size * 8 + frame.composition.objects.count { it.crop != null } * 16 +
            frame.objects.sumOf { 10 + it.bytes.size }
        val bytes = ByteArrayOutputStream(capacity)
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeByte(VERSION)
            output.writeBoolean(frame != null)
            if (frame != null) writeState(output, frame)
        }
        return bytes.toByteArray()
    }

    fun decode(data: ByteArray, offset: Int = 0, length: Int = data.size): List<Cue> {
        if (length !in 6..MAX_SAMPLE_BYTES || offset < 0 || offset > data.size - length) return emptyList()
        return try {
            val input = DataInputStream(ByteArrayInputStream(data, offset, length))
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) return emptyList()
            val present = input.readUnsignedByte()
            if (present != 1) return emptyList()
            val state = readState(input) ?: return emptyList()
            if (input.available() != 0 || !validPixelBudget(state)) return emptyList()
            MultiObjectPgsParser().restoreSnapshot(state)
        } catch (_: IOException) {
            emptyList()
        }
    }

    private fun writeState(output: DataOutputStream, state: State) {
        val frame = state.composition
        output.writeShort(frame.width)
        output.writeShort(frame.height)
        output.writeByte(frame.palette)
        output.writeByte(frame.objects.size)
        state.palette.forEach(output::writeInt)
        for (placement in frame.objects) {
            output.writeShort(placement.id)
            output.writeByte(placement.window)
            output.writeShort(placement.x)
            output.writeShort(placement.y)
            output.writeBoolean(placement.crop != null)
            placement.crop?.let { writeRect(output, it) }
        }
        output.writeShort(state.windows.size)
        for ((id, window) in state.windows) {
            output.writeByte(id)
            writeRect(output, window)
        }
        output.writeShort(state.objects.size)
        for (value in state.objects) {
            output.writeShort(value.id)
            output.writeShort(value.width)
            output.writeShort(value.height)
            output.writeInt(value.bytes.size)
            output.write(value.bytes)
        }
    }

    private fun readState(input: DataInputStream): State? {
        val width = input.readUnsignedShort()
        val height = input.readUnsignedShort()
        if (width == 0 || height == 0 || width.toLong() * height > PgsBitmapObjects.MAX_PIXELS) return null
        val paletteId = input.readUnsignedByte()
        val count = input.readUnsignedByte()
        if (count == 0) return null
        val palette = IntArray(PALETTE_SIZE) { input.readInt() }
        val placements = ArrayList<MultiObjectPgsParser.Placement>(count)
        repeat(count) { placements.add(readPlacement(input) ?: return null) }
        val windowCount = input.readUnsignedShort()
        if (windowCount > PALETTE_SIZE) return null
        val windows = HashMap<Int, Rect>()
        repeat(windowCount) {
            val id = input.readUnsignedByte()
            val rect = readRect(input) ?: return null
            if (windows.put(id, rect) != null || placements.none { it.window == id }) return null
        }
        val objects = readObjects(input) ?: return null
        val frame = MultiObjectPgsParser.Composition(width, height, paletteId, placements)
        return State(frame, palette, windows, objects)
    }

    private fun readPlacement(input: DataInputStream): MultiObjectPgsParser.Placement? {
        val id = input.readUnsignedShort()
        val window = input.readUnsignedByte()
        val x = input.readUnsignedShort()
        val y = input.readUnsignedShort()
        val cropped = input.readUnsignedByte()
        if (cropped !in 0..1) return null
        val crop = if (cropped == 1) readRect(input)?.takeUnless { it.isEmpty } ?: return null else null
        return MultiObjectPgsParser.Placement(id, window, x, y, crop)
    }

    private fun readObjects(input: DataInputStream): List<PgsBitmapObjects.Encoded>? {
        val count = input.readUnsignedShort()
        if (count !in 1..PgsBitmapObjects.MAX_OBJECTS) return null
        val objects = ArrayList<PgsBitmapObjects.Encoded>(count)
        val ids = HashSet<Int>()
        var allocated = 0L
        repeat(count) {
            val id = input.readUnsignedShort()
            val width = input.readUnsignedShort()
            val height = input.readUnsignedShort()
            val size = input.readInt()
            allocated += size
            if (!ids.add(id) || width == 0 || height == 0 || width.toLong() * height > PgsBitmapObjects.MAX_PIXELS) return null
            if (size !in 1..PgsBitmapObjects.MAX_OBJECT_BYTES || size > input.available()) return null
            if (allocated > PgsBitmapObjects.MAX_CACHE_BYTES) return null
            val bytes = ByteArray(size)
            input.readFully(bytes)
            objects.add(PgsBitmapObjects.Encoded(id, width, height, bytes))
        }
        return objects
    }

    private fun validPixelBudget(state: State): Boolean {
        val objects = state.objects.associateBy { it.id }
        if (objects.keys != state.composition.objects.map { it.id }.toSet()) return false
        var pixels = 0L
        for (placement in state.composition.objects) {
            val value = objects[placement.id] ?: return false
            pixels += value.width.toLong() * value.height
            if (pixels > MAX_DECODED_PIXELS) return false
            val crop = placement.crop ?: continue
            if (crop.left < 0 || crop.top < 0 || crop.right > value.width || crop.bottom > value.height) return false
        }
        return true
    }

    private fun writeRect(output: DataOutputStream, rect: Rect) {
        output.writeInt(rect.left)
        output.writeInt(rect.top)
        output.writeInt(rect.right)
        output.writeInt(rect.bottom)
    }

    private fun readRect(input: DataInputStream): Rect? {
        val rect = Rect(input.readInt(), input.readInt(), input.readInt(), input.readInt())
        if (rect.left !in 0..65535 || rect.top !in 0..65535) return null
        if (rect.width() !in 0..65535 || rect.height() !in 0..65535) return null
        return rect
    }
}

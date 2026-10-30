@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.DeflaterOutputStream

/** Compares independent queue samples with the existing parser using real Bitmap APIs. */
class PgsSnapshotCodecTest {
    fun runAll() {
        testPaletteDeltasAndObjectReuseAreIndependent()
        testFragmentedObjectsAndMultipleEnds()
        testWindowsCropAndInvisibleObjects()
        testClearInvalidAndReset()
        testCompressedInputAndOffsets()
        testMalformedSnapshotsAndBudgets()
        testObjectCacheExportAndImportBounds()
    }

    fun testPaletteDeltasAndObjectReuseAreIndependent() {
        val samples = listOf(
            join(composition(listOf(Placement(1))), palette(), objectData(1, bytes(1, 2)), end()),
            join(composition(listOf(Placement(1, x = 40)), epoch = false),
                segment(0x14, bytes(0, 1, 1, 0, 128, 128, 255)), end()),
            join(composition(listOf(Placement(1, x = 60)), epoch = false), end()),
        )
        val (snapshots, cues) = compare(samples)
        check(snapshots[0].size < 2048)
        check(cues[0].single().bitmap!!.getPixel(0, 0) != cues[1].single().bitmap!!.getPixel(0, 0))
        check(cues[1].single().bitmap!!.getPixel(1, 0) == cues[0].single().bitmap!!.getPixel(1, 0))
        val order = (snapshots.indices.toList() + snapshots.indices.toList()).toMutableList()
        java.util.Collections.shuffle(order, Random(42))
        for (index in order) assertCues(cues[index], PgsSnapshotCodec.decode(snapshots[index]))
    }

    fun testFragmentedObjectsAndMultipleEnds() {
        val samples = listOf(
            join(composition(listOf(Placement(0x101))), palette(),
                objectData(0x101, bytes(1), flags = 0x80, declared = 2)),
            join(segment(0x15, short(0x101) + bytes(0, 0x40, 2)), end(), end(),
                composition(listOf(Placement(0x101, y = 70)), epoch = false), end()),
        )
        val (snapshots, cues) = compare(samples)
        check(snapshots.size == 2)
        check(cues[0].single().bitmap!!.getPixel(0, 0) != cues[0].single().bitmap!!.getPixel(1, 0))
        assertCues(cues[1], PgsSnapshotCodec.decode(snapshots[1]))
    }

    fun testWindowsCropAndInvisibleObjects() {
        val placements = listOf(Placement(1, crop = listOf(1, 0, 1, 1)),
            Placement(1, x = 120), Placement(2, x = 59, window = 1))
        val windows = segment(0x17, bytes(2, 0) + short(20) + short(30) + short(2) + short(1) +
            bytes(1) + short(60) + short(30) + short(1) + short(1))
        val (_, cues) = compare(listOf(join(composition(placements), palette(), windows,
            objectData(1, bytes(1, 2)), objectData(2, bytes(1, 2)), end())))
        check(cues.single().size == 2)
        check(cues.single()[0].bitmap!!.width == 1)
        check(cues.single()[0].position == 0.2f)
        check(cues.single()[1].position == 0.6f)
    }

    fun testClearInvalidAndReset() {
        val loader = MultiObjectPgsParser()
        val output = ArrayList<ByteArray>()
        fun feed(vararg data: ByteArray) {
            val sample = join(*data)
            loader.consumeSnapshots(sample, 0, sample.size, Consumer { output.add(it) })
        }
        feed(composition(listOf(Placement(1))), palette(), objectData(1, bytes(1, 1)), end())
        feed(composition(emptyList(), epoch = false), end())
        feed(composition(listOf(Placement(1)), epoch = false), bytes(0x15, 0, 30, 0))
        feed(end())
        loader.reset()
        feed(composition(listOf(Placement(1)), epoch = false), end())
        feed(composition(listOf(Placement(1)), epoch = false), palette(),
            objectData(1, bytes(0, 0x83, 1)), end())
        check(output.size == 5)
        check(PgsSnapshotCodec.decode(output.first()).size == 1)
        output.drop(1).forEach { check(PgsSnapshotCodec.decode(it).isEmpty()) }
        feed(composition(listOf(Placement(1)), epoch = false), objectData(1, bytes(1, 1)), end())
        check(PgsSnapshotCodec.decode(output.last()).size == 1)
        feed(composition(listOf(Placement(1)), epoch = true), end())
        check(PgsSnapshotCodec.decode(output.last()).isEmpty())
    }

    fun testCompressedInputAndOffsets() {
        val sample = join(composition(listOf(Placement(1))), palette(), objectData(1, bytes(1, 2)), end())
        val compressed = ByteArrayOutputStream().apply {
            DeflaterOutputStream(this).use { it.write(sample) }
        }.toByteArray()
        val (snapshots, cues) = compare(listOf(compressed))
        val padded = bytes(11, 12) + snapshots.single() + bytes(13)
        assertCues(cues.single(), PgsSnapshotCodec.decode(padded, 2, snapshots.single().size))
        val loader = MultiObjectPgsParser()
        val output = ArrayList<ByteArray>()
        val input = bytes(0) + sample + bytes(0)
        loader.consumeSnapshots(input, 1, sample.size, Consumer { output.add(it) })
        assertCues(cues.single(), PgsSnapshotCodec.decode(output.single()))
    }

    fun testMalformedSnapshotsAndBudgets() {
        val (snapshots, _) = compare(listOf(join(composition(listOf(Placement(1))),
            palette(), objectData(1, bytes(1, 2)), end())))
        val sample = snapshots.single()
        for (length in sample.indices) check(PgsSnapshotCodec.decode(sample, 0, length).isEmpty())
        check(PgsSnapshotCodec.decode(sample, -1, sample.size).isEmpty())
        check(PgsSnapshotCodec.decode(sample, 1, Int.MAX_VALUE).isEmpty())
        check(PgsSnapshotCodec.decode(ByteArray(6), Int.MAX_VALUE, 6).isEmpty())
        check(PgsSnapshotCodec.decode(sample + bytes(0)).isEmpty())
        check(PgsSnapshotCodec.decode(sample.copyOf().apply { this[4] = 2 }).isEmpty())
        check(PgsSnapshotCodec.decode(sample.copyOf().apply { this[11] = 0xff.toByte() }).isEmpty())
        // Header, full palette, one uncropped placement and empty window table precede objects.
        val objectOffset = 6 + 6 + 256 * 4 + 8 + 2 + 2
        fun corrupt(offset: Int, data: ByteArray) = sample.copyOf().apply { data.copyInto(this, offset) }
        check(PgsSnapshotCodec.decode(corrupt(objectOffset + 6, bytes(127, 255, 255, 255))).isEmpty())
        check(PgsSnapshotCodec.decode(corrupt(objectOffset + 6, bytes(255, 255, 255, 255))).isEmpty())
        check(PgsSnapshotCodec.decode(corrupt(objectOffset + 2, short(65535) + short(65535))).isEmpty())
        check(PgsSnapshotCodec.decode(corrupt(objectOffset - 2, short(257))).isEmpty())
        val (twice, _) = compare(listOf(join(composition(listOf(Placement(1), Placement(1, x = 40))),
            palette(), objectData(1, bytes(1, 2)), end())))
        val oversized = twice.single().copyOf()
        (short(4000) + short(2000)).copyInto(oversized, objectOffset + 8 + 2)
        check(PgsSnapshotCodec.decode(oversized).isEmpty())
        check(PgsSnapshotCodec.decode(ByteArray(PgsSnapshotCodec.MAX_SAMPLE_BYTES + 1)).isEmpty())
    }

    fun testObjectCacheExportAndImportBounds() {
        val objects = PgsBitmapObjects()
        repeat(257) { id ->
            val segment = objectData(id, bytes(1, 2))
            objects.read(PgsBytes(segment, 3))
        }
        check(objects.exportEncoded(listOf(0)) == null)
        check(objects.exportEncoded(listOf(1, 256))!!.size == 2)
        val encoded = objects.exportEncoded(listOf(256, 256))!!
        check(encoded.size == 1)
        val restored = PgsBitmapObjects()
        check(restored.importEncoded(encoded))
        check(!restored.importEncoded(encoded + encoded))
        check(!restored.importEncoded(listOf(PgsBitmapObjects.Encoded(1, 65535, 65535, bytes(1)))))
        check(!restored.importEncoded(listOf(PgsBitmapObjects.Encoded(1, 1, 1,
            ByteArray(PgsBitmapObjects.MAX_OBJECT_BYTES + 1)))))
        val large = ByteArray(PgsBitmapObjects.MAX_OBJECT_BYTES)
        check(!restored.importEncoded((1..3).map { PgsBitmapObjects.Encoded(it, 1, 1, large) }))
        objects.clear()
        check(objects.exportEncoded(listOf(256)) == null)
    }

    private fun compare(samples: List<ByteArray>): Pair<List<ByteArray>, List<List<Cue>>> {
        val parser = MultiObjectPgsParser()
        val loader = MultiObjectPgsParser()
        val snapshots = ArrayList<ByteArray>()
        val cues = ArrayList<List<Cue>>()
        for (sample in samples) {
            parser.consume(sample, 0, sample.size, Consumer<CuesWithTiming> { cues.add(it.cues) })
            loader.consumeSnapshots(sample, 0, sample.size, Consumer { snapshots.add(it) })
        }
        check(cues.size == snapshots.size)
        snapshots.indices.forEach { assertCues(cues[it], PgsSnapshotCodec.decode(snapshots[it])) }
        return snapshots to cues
    }

    private fun assertCues(expected: List<Cue>, actual: List<Cue>) {
        check(expected.size == actual.size)
        for (index in expected.indices) {
            val first = expected[index]
            val second = actual[index]
            check(first.position == second.position && first.line == second.line)
            check(first.size == second.size && first.bitmapHeight == second.bitmapHeight)
            check(first.positionAnchor == second.positionAnchor && first.lineAnchor == second.lineAnchor)
            check(first.bitmap!!.sameAs(second.bitmap!!))
        }
    }

    private data class Placement(
        val id: Int,
        val x: Int = 20,
        val y: Int = 30,
        val window: Int = 0,
        val crop: List<Int>? = null,
    )

    private fun composition(placements: List<Placement>, epoch: Boolean = true): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(bytes(0, 100, 0, 100, 0x10, 0, 0, if (epoch) 0x80 else 0, 0, 0, placements.size))
        for (placement in placements) {
            body.write(short(placement.id) + bytes(placement.window, if (placement.crop == null) 0 else 0x80))
            body.write(short(placement.x) + short(placement.y))
            placement.crop?.forEach { body.write(short(it)) }
        }
        return segment(0x16, body.toByteArray())
    }

    private fun objectData(id: Int, rle: ByteArray, flags: Int = 0xc0, declared: Int = rle.size): ByteArray {
        val size = declared + 4
        return segment(0x15, short(id) + bytes(0, flags, size shr 16, size shr 8, size, 0, 2, 0, 1) + rle)
    }

    private fun palette() = segment(0x14, bytes(0, 0, 1, 255, 128, 128, 255, 2, 0, 128, 128, 255))
    private fun join(vararg data: ByteArray) = ByteArrayOutputStream().apply { data.forEach { write(it) } }.toByteArray()
    private fun end() = segment(0x80, byteArrayOf())
    private fun segment(type: Int, body: ByteArray) = bytes(type) + short(body.size) + body
    private fun short(value: Int) = bytes(value shr 8, value)
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}

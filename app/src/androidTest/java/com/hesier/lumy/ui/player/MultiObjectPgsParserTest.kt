@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.graphics.Color
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import java.io.ByteArrayOutputStream

/** Uses real Android Bitmap APIs without adding a test framework dependency. */
class MultiObjectPgsParserTest {
    fun runAll() {
        val tests: List<(MultiObjectPgsParserTest) -> Unit> = listOf(
            { it.testTwoObjectsKeepTheirPixelsAndPositions() },
            { it.testInvisibleObjectDoesNotHideVisibleObject() },
            { it.testUncoloredRunIsTransparentRegardlessOfPaletteZero() },
            { it.testZeroObjectCompositionClearsPreviousCue() },
            { it.testFragmentedObjectAndPaletteSurviveAcrossSamples() },
            { it.testResetDropsCachedPaletteAndObjects() },
            { it.testRleRunCannotCrossObjectWidth() },
            { it.testTruncatedRleColorIsRejected() },
            { it.testTruncatedSegmentDoesNotDisplayPartialComposition() },
            { it.testCropUsesObjectPixelsAndPreservesCanvasCoordinates() },
        )
        tests.forEach { it(MultiObjectPgsParserTest()) }
    }

    private val parser = MultiObjectPgsParser()

    fun testUncoloredRunIsTransparentRegardlessOfPaletteZero() {
        val opaqueZero = segment(0x14, bytes(0, 0, 0, 235, 128, 128, 255))
        val cue = parse(composition(listOf(Placement(1, 20, 30))), opaqueZero,
            objectData(1, bytes(0, 2)), end()).single().cues.single()
        assertEquals(0, cue.bitmap!!.getPixel(0, 0))
        assertEquals(0, cue.bitmap!!.getPixel(1, 0))
    }

    fun testInvisibleObjectDoesNotHideVisibleObject() {
        val output = parse(
            composition(listOf(Placement(1, 20, 30), Placement(2, 120, 30))),
            palette(), objectData(1, bytes(1, 1)), objectData(2, bytes(2, 2)), end(),
        ).single()
        assertEquals(1, output.cues.size)
        assertEquals(Color.WHITE, output.cues.single().bitmap!!.getPixel(0, 0))
    }

    fun testTwoObjectsKeepTheirPixelsAndPositions() {
        val output = parse(
            composition(listOf(Placement(0x101, 20, 30), Placement(0x202, 60, 70))),
            palette(), objectData(0x101, bytes(1, 1)), objectData(0x202, bytes(2, 2)), end(),
        ).single()
        assertEquals(C.TIME_UNSET, output.startTimeUs)
        assertEquals(C.TIME_UNSET, output.durationUs)
        assertEquals(2, output.cues.size)
        val first = output.cues[0]
        val second = output.cues[1]
        assertEquals(Color.WHITE, first.bitmap!!.getPixel(0, 0))
        assertEquals(Color.BLACK, second.bitmap!!.getPixel(0, 0))
        assertEquals(0.2f, first.position, EPSILON)
        assertEquals(0.3f, first.line, EPSILON)
        assertEquals(0.6f, second.position, EPSILON)
        assertEquals(0.7f, second.line, EPSILON)
        assertEquals(0.02f, first.size, EPSILON)
        assertEquals(0.01f, first.bitmapHeight, EPSILON)
        assertEquals(Cue.ANCHOR_TYPE_START, first.positionAnchor)
        assertEquals(Cue.ANCHOR_TYPE_START, first.lineAnchor)
    }

    fun testZeroObjectCompositionClearsPreviousCue() {
        showWhiteObject()
        val output = parse(composition(emptyList(), epoch = false), end())
        assertEquals(1, output.size)
        assertTrue(output.single().cues.isEmpty())
    }

    fun testFragmentedObjectAndPaletteSurviveAcrossSamples() {
        val id = 0x101
        assertTrue(parse(composition(listOf(Placement(id, 20, 30))), palette()).isEmpty())
        assertTrue(parse(objectData(id, bytes(1), sequence = 0x80, declaredSize = 2)).isEmpty())
        val continuation = segment(0x15, bytes(1, 1, 0, 0x40, 2))
        val first = parse(continuation, end()).single().cues.single()
        assertEquals(Color.WHITE, first.bitmap!!.getPixel(0, 0))
        assertEquals(Color.BLACK, first.bitmap!!.getPixel(1, 0))
        val reused = parse(composition(listOf(Placement(id, 40, 50)), epoch = false), end())
            .single().cues.single()
        assertEquals(Color.BLACK, reused.bitmap!!.getPixel(1, 0))
        assertEquals(0.4f, reused.position, EPSILON)
        assertEquals(0.5f, reused.line, EPSILON)
    }

    fun testResetDropsCachedPaletteAndObjects() {
        showWhiteObject()
        parser.reset()
        val missing = parse(composition(listOf(Placement(1, 20, 30)), epoch = false), end())
        assertTrue(missing.single().cues.isEmpty())
        val restored = parse(
            composition(listOf(Placement(1, 20, 30)), epoch = false),
            palette(), objectData(1, bytes(2, 2)), end(),
        )
        assertEquals(Color.BLACK, restored.single().cues.single().bitmap!!.getPixel(0, 0))
    }

    fun testRleRunCannotCrossObjectWidth() {
        val output = parse(
            composition(listOf(Placement(1, 20, 30))), palette(),
            objectData(1, bytes(0, 0x83, 1)), end(),
        )
        assertTrue(output.single().cues.isEmpty())
    }

    fun testTruncatedRleColorIsRejected() {
        val output = parse(
            composition(listOf(Placement(1, 20, 30))), palette(),
            objectData(1, bytes(0, 0x82)), end(),
        )
        assertTrue(output.single().cues.isEmpty())
    }

    fun testTruncatedSegmentDoesNotDisplayPartialComposition() {
        assertTrue(parse(
            composition(listOf(Placement(1, 20, 30))), palette(), objectData(1, bytes(1, 1)),
            bytes(0x15, 0, 20, 0),
        ).isEmpty())
        assertTrue(parse(end()).single().cues.isEmpty())
    }

    fun testCropUsesObjectPixelsAndPreservesCanvasCoordinates() {
        val placement = Placement(1, 20, 30, crop = listOf(1, 0, 1, 1))
        val cue = parse(
            composition(listOf(placement)), palette(), objectData(1, bytes(1, 2)), end(),
        ).single().cues.single()
        assertEquals(1, cue.bitmap!!.width)
        assertEquals(Color.BLACK, cue.bitmap!!.getPixel(0, 0))
        assertEquals(0.2f, cue.position, EPSILON)
        assertEquals(0.01f, cue.size, EPSILON)
    }

    private fun showWhiteObject() {
        assertEquals(1, parse(
            composition(listOf(Placement(1, 20, 30))), palette(), objectData(1, bytes(1, 1)), end(),
        ).single().cues.size)
    }

    private fun parse(vararg segments: ByteArray): List<CuesWithTiming> {
        val sample = ByteArrayOutputStream().apply { segments.forEach { write(it) } }.toByteArray()
        val output = ArrayList<CuesWithTiming>()
        parser.parse(sample, 0, sample.size, SubtitleParser.OutputOptions.allCues(), Consumer { output.add(it) })
        return output
    }

    private data class Placement(val id: Int, val x: Int, val y: Int, val crop: List<Int>? = null)

    private fun composition(placements: List<Placement>, epoch: Boolean = true): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(bytes(0, 100, 0, 100, 0x10, 0, 0, if (epoch) 0x80 else 0, 0, 0, placements.size))
        for (placement in placements) {
            body.write(short(placement.id))
            body.write(bytes(0, if (placement.crop == null) 0 else 0x80))
            body.write(short(placement.x))
            body.write(short(placement.y))
            placement.crop?.forEach { body.write(short(it)) }
        }
        return segment(0x16, body.toByteArray())
    }

    private fun palette() = segment(0x14, bytes(0, 0, 1, 255, 128, 128, 255, 2, 0, 128, 128, 255))

    private fun objectData(
        id: Int,
        rle: ByteArray,
        sequence: Int = 0xc0,
        declaredSize: Int = rle.size,
    ): ByteArray {
        val size = declaredSize + 4
        return segment(0x15, short(id) + bytes(0, sequence, size shr 16, size shr 8, size, 0, 2, 0, 1) + rle)
    }

    private fun end() = segment(0x80, byteArrayOf())
    private fun segment(type: Int, body: ByteArray) = bytes(type) + short(body.size) + body
    private fun short(value: Int) = bytes(value shr 8, value)
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun assertTrue(actual: Boolean) {
        check(actual) { "Expected true" }
    }

    private fun assertEquals(expected: Any?, actual: Any?) {
        check(expected == actual) { "Expected <$expected>, actual <$actual>" }
    }

    private fun assertEquals(expected: Float, actual: Float, delta: Float) {
        check(kotlin.math.abs(expected - actual) <= delta) { "Expected <$expected>, actual <$actual>" }
    }

    companion object {
        private const val EPSILON = 0.00001f
    }
}

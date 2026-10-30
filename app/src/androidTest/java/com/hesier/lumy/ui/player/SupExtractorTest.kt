@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayInputStream
import java.io.EOFException

class SupExtractorTest {
    fun runAll() {
        val capture = Capture()
        val extractor = extractor(capture)
        val data = display(90_000) + display(270_000)
        val input = input(data)
        check(extractor.sniff(input))
        input.resetPeekPosition()
        readAll(extractor, input)
        check(capture.times == listOf(1_000_000L, 3_000_000L)) { capture.times.toString() }
        check(capture.format?.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES)
        val point = capture.seekMap!!.getSeekPoints(2_000_000L).first
        check(point.timeUs == 1_000_000L && point.position == 0L)
        val second = capture.seekMap!!.getSeekPoints(3_000_000L).first
        check(second.position == display(90_000).size.toLong())
        extractor.seek(second.position, second.timeUs)
        readAll(extractor, input(display(270_000), second.position))
        check(capture.times.last() == 3_000_000L)
        val warmup = Capture()
        val seeking = extractor(warmup)
        seeking.seek(0, 2_000_000L)
        readAll(seeking, input(data))
        check(warmup.times == listOf(2_000_000L, 3_000_000L)) { "Historical SUP cues were replayed: ${warmup.times}" }
        expectFailure<ParserException> { readAll(extractor(Capture()), input(ByteArray(13))) }
        expectFailure<EOFException> { readAll(extractor(Capture()), input(display(90_000).copyOf(15))) }
    }

    private fun extractor(capture: Capture) = SupExtractor(
        Format.Builder().setId("ext:test").setSampleMimeType(MimeTypes.APPLICATION_PGS).build(),
    ).apply { init(capture) }

    private fun input(data: ByteArray, offset: Long = 0L): DefaultExtractorInput {
        val stream = ByteArrayInputStream(data)
        return DefaultExtractorInput(DataReader { target, start, size -> stream.read(target, start, size) }, offset, offset + data.size)
    }

    private fun readAll(extractor: SupExtractor, input: DefaultExtractorInput) {
        val holder = PositionHolder()
        while (extractor.read(input, holder) != Extractor.RESULT_END_OF_INPUT) Unit
    }

    private fun display(pts: Int): ByteArray {
        val composition = byteArrayOf(0, 100, 0, 100, 0x10, 0, 0, 0x80.toByte(), 0, 0, 0)
        return packet(0x16, pts, composition) + packet(0x80, pts, byteArrayOf())
    }

    private fun packet(type: Int, pts: Int, body: ByteArray): ByteArray {
        val header = byteArrayOf(0x50, 0x47) + intBytes(pts) + intBytes(pts + 90_000) +
            byteArrayOf(type.toByte(), (body.size shr 8).toByte(), body.size.toByte())
        return header + body
    }

    private fun intBytes(value: Int) = byteArrayOf(
        (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte(),
    )

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        try { block() } catch (failure: Throwable) {
            check(failure is T) { "Expected ${T::class.java}, got $failure" }
            return
        }
        error("Expected ${T::class.java}")
    }

    private class Capture : ExtractorOutput, TrackOutput {
        val times = mutableListOf<Long>()
        var format: Format? = null
        var seekMap: SeekMap? = null
        override fun track(id: Int, type: Int): TrackOutput = this
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) { this.seekMap = seekMap }
        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            input.read(ByteArray(length), 0, length)
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length) }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            check(flags and C.BUFFER_FLAG_KEY_FRAME != 0)
            times.add(timeUs)
        }
    }
}

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory

class PgsSnapshotTrackOutputTest {
    fun runAll() {
        val sink = Sink()
        val ids = hashSetOf<String?>()
        val output = PgsSnapshotTrackOutput(sink, ids, PgsSelection.Track("4"))
        output.format(format("4"))
        check(sink.format!!.sampleMimeType == PgsSnapshotCodec.MIME_TYPE)
        val first = sample()
        val clear = segment(0x16, bytes(0, 100, 0, 100, 0x10, 0, 1, 0, 0, 0, 0)) + segment(0x80, byteArrayOf())
        // Metadata may refer to an earlier sample while bytes for the next are already buffered.
        output.sampleData(ParsableByteArray(first + clear), first.size + clear.size)
        output.sampleMetadata(100, 0, first.size, clear.size, null)
        output.sampleMetadata(200, 0, clear.size, 0, null)
        check(sink.samples.map { it.first } == listOf(100L, 200L))
        check(PgsSnapshotCodec.decode(sink.samples[0].second).size == 1)
        check(PgsSnapshotCodec.decode(sink.samples[1].second).isEmpty())
        check(sink.flags.all { it and C.BUFFER_FLAG_KEY_FRAME != 0 })
        val supplemental = java.nio.ByteBuffer.allocate(4 + first.size + 3)
            .putInt(first.size).put(first).put(bytes(7, 8, 9)).array()
        output.sampleData(ParsableByteArray(supplemental), supplemental.size)
        output.sampleMetadata(250, C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA, supplemental.size, 0, null)
        check(PgsSnapshotCodec.decode(sink.samples.last().second).size == 1)
        check(sink.flags.last() and C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA == 0)
        output.reset()
        val reused = segment(0x16, bytes(0, 100, 0, 100, 0x10, 0, 1, 0, 0, 0, 1, 0, 1, 0, 0, 0, 20, 0, 30)) +
            segment(0x80, byteArrayOf())
        output.sampleData(ParsableByteArray(reused), reused.size)
        output.sampleMetadata(300, 0, reused.size, 0, null)
        check(PgsSnapshotCodec.decode(sink.samples.last().second).isEmpty())
        checkSelection(PgsSelection.Off, hashSetOf(), "4", false)
        checkSelection(PgsSelection.Track("4"), hashSetOf("4"), "5", false)
        checkSelection(PgsSelection.Track("4"), hashSetOf(), "10", true)
        checkDelegation()
    }

    private fun checkSelection(selection: PgsSelection, ids: MutableSet<String?>, id: String, expected: Boolean) {
        val sink = Sink()
        val output = PgsSnapshotTrackOutput(sink, ids, selection)
        output.format(format(id))
        val sample = sample()
        output.sampleData(ParsableByteArray(sample), sample.size)
        output.sampleMetadata(0, 0, sample.size, 0, null)
        check(sink.samples.isNotEmpty() == expected)
    }

    private fun checkDelegation() {
        val factory = PgsSnapshotParserFactory(DefaultSubtitleParserFactory())
        check(!factory.supportsFormat(format("4")))
        val format = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build()
        check(factory.supportsFormat(format))
        val sink = Sink()
        val output = PgsSnapshotTrackOutput(sink, hashSetOf(), PgsSelection.Off)
        output.format(format)
        output.sampleData(DataReader { target, offset, _ -> target[offset] = 42; 1 }, 1, false)
        output.sampleMetadata(900, 0, 1, 0, null)
        check(sink.format === format && sink.samples.single().second.contentEquals(bytes(42)))
    }

    private class Sink : TrackOutput {
        var format: Format? = null
        private var pending = byteArrayOf()
        val samples = mutableListOf<Pair<Long, ByteArray>>()
        val flags = mutableListOf<Int>()
        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val buffer = ByteArray(length)
            val count = input.read(buffer, 0, length)
            if (count > 0) pending += buffer.copyOf(count)
            return count
        }
        override fun sampleData(input: ParsableByteArray, length: Int, sampleDataPart: Int) {
            pending += ByteArray(length).also { input.readBytes(it, 0, length) }
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            samples.add(timeUs to pending.copyOfRange(pending.size - offset - size, pending.size - offset))
            this.flags.add(flags)
            pending = pending.takeLast(offset).toByteArray()
        }
    }

    private fun format(id: String) = Format.Builder().setId(id).setSampleMimeType(MimeTypes.APPLICATION_PGS).build()
    private fun sample() =
        segment(0x16, bytes(0, 100, 0, 100, 0x10, 0, 0, 0x80, 0, 0, 1, 0, 1, 0, 0, 0, 20, 0, 30)) +
            segment(0x14, bytes(0, 0, 1, 255, 128, 128, 255)) +
            segment(0x15, bytes(0, 1, 0, 0xc0, 0, 0, 6, 0, 2, 0, 1, 1, 1)) + segment(0x80, byteArrayOf())
    private fun segment(type: Int, body: ByteArray) = bytes(type, body.size shr 8, body.size) + body
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}

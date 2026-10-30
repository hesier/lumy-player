@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import com.hesier.lumy.util.DebugLog
import java.io.EOFException

/** Keep only PGS out of Media3's bitmap-to-PNG transcoding; text/ASS retain their existing path. */
internal class PgsSnapshotParserFactory(private val delegate: SubtitleParser.Factory) : SubtitleParser.Factory by delegate {
    override fun supportsFormat(format: Format): Boolean =
        format.sampleMimeType != MimeTypes.APPLICATION_PGS && delegate.supportsFormat(format)
}

internal class PgsSnapshotExtractorOutput(
    private val delegate: ExtractorOutput,
    private val selection: PgsSelection,
) : ExtractorOutput by delegate {
    private val ids = HashSet<String?>()
    private val tracks = mutableListOf<PgsSnapshotTrackOutput>()

    override fun track(id: Int, type: Int): TrackOutput =
        PgsSnapshotTrackOutput(delegate.track(id, type), ids, selection).also(tracks::add)

    fun reset() = tracks.forEach(PgsSnapshotTrackOutput::reset)
}

/** Emit independently decodable compressed state, with no Bitmap allocation on the MKV loader. */
internal class PgsSnapshotTrackOutput(
    private val delegate: TrackOutput,
    private val ids: MutableSet<String?>,
    private val selection: PgsSelection,
) : TrackOutput {
    private var source: Format? = null
    private var parser = MultiObjectPgsParser()
    private var data = ByteArray(0)
    private var end = 0
    private var lastRender: Boolean? = null
    private val scratch = ParsableByteArray()
    private val isPgs get() = source?.sampleMimeType == MimeTypes.APPLICATION_PGS

    override fun format(format: Format) {
        source = format
        if (!isPgs) { delegate.format(format); return }
        ids.add(format.id)
        delegate.format(format.buildUpon().setSampleMimeType(PgsSnapshotCodec.MIME_TYPE)
            .setCodecs(MimeTypes.APPLICATION_PGS).setSubsampleOffsetUs(Format.OFFSET_SAMPLE_RELATIVE).build())
    }

    fun reset() {
        parser.reset()
        end = 0
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (!isPgs) return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        ensure(length)
        val count = input.read(data, end, length)
        if (count == C.RESULT_END_OF_INPUT) {
            if (!allowEndOfInput) throw EOFException()
        } else end += count
        return count
    }

    override fun sampleData(input: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (!isPgs) { delegate.sampleData(input, length, sampleDataPart); return }
        ensure(length)
        input.readBytes(data, end, length)
        end += length
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (!isPgs) { delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData); return }
        val start = end - offset - size
        require(size >= 0 && offset >= 0 && start >= 0 && cryptoData == null)
        val render = when (selection) {
            PgsSelection.All -> true
            PgsSelection.Off -> false
            is PgsSelection.Track -> selection.id !in ids || selection.id == source?.id
        }
        if (lastRender != render) {
            lastRender = render
            DebugLog.i("LumyPlayer", "pgs snapshot track=${source?.id} render=$render selection=$selection")
        }
        var payloadStart = start
        var payloadSize = size
        val supplemental = flags and C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA != 0
        if (supplemental) {
            require(size >= 4)
            scratch.reset(data, end)
            scratch.setPosition(start)
            payloadSize = scratch.readUnsignedIntToInt()
            payloadStart += 4
            require(payloadSize <= size - 4)
        }
        if (render) parser.consumeSnapshots(data, payloadStart, payloadSize) { snapshot ->
            scratch.reset(snapshot)
            delegate.sampleData(scratch, snapshot.size)
            // Each snapshot carries its own palette/objects, including when read after a queue seek.
            delegate.sampleMetadata(timeUs, (flags and C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA.inv()) or
                C.BUFFER_FLAG_KEY_FRAME, snapshot.size, 0, null)
        }
        if (offset > 0) data.copyInto(data, 0, end - offset, end)
        end = offset
    }

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    private fun ensure(length: Int) {
        require(length >= 0 && length <= MAX_RAW_SAMPLE_BYTES - end) { "PGS sample exceeds byte budget" }
        if (data.size < end + length) data = data.copyOf(maxOf(end + length,
            minOf(MAX_RAW_SAMPLE_BYTES, maxOf(4096, data.size * 2))))
    }

    private companion object {
        const val MAX_RAW_SAMPLE_BYTES = 32 * 1024 * 1024
    }
}

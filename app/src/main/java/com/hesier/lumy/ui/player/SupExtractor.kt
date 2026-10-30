@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.text.Cue
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueEncoder
import com.hesier.lumy.util.DebugLog
import java.io.ByteArrayOutputStream
import java.util.TreeMap

/** Emit one PGS display set at its PCS presentation timestamp; never expand the entire SUP. */
internal class SupExtractor(
    private val format: Format,
) : Extractor {
    private val header = ByteArray(13)
    private val display = ByteArrayOutputStream()
    private val index = SupSeekMap()
    private val parser = MultiObjectPgsParser()
    private val encoder = CueEncoder()
    private lateinit var track: TrackOutput
    private var timeUs = C.TIME_UNSET
    private var displayStart = 0L
    private var lastPts = 0L
    private var rollover = 0L
    private var seekTimeUs = 0L
    private var lastDisplayUs = C.TIME_UNSET
    private var started = false

    override fun sniff(input: ExtractorInput): Boolean {
        val magic = ByteArray(2)
        return input.peekFully(magic, 0, 2, true) && magic[0] == 0x50.toByte() && magic[1] == 0x47.toByte()
    }

    override fun init(extractorOutput: ExtractorOutput) {
        track = extractorOutput.track(0, C.TRACK_TYPE_TEXT)
        track.format(format.buildUpon().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.APPLICATION_PGS).setCueReplacementBehavior(parser.cueReplacementBehavior).build())
        extractorOutput.endTracks()
        extractorOutput.seekMap(index)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val position = input.position
        if (!input.readFully(header, 0, header.size, true)) {
            if (!started && lastDisplayUs != C.TIME_UNSET) emit(parser.currentCues(), seekTimeUs)
            started = true
            return Extractor.RESULT_END_OF_INPUT
        }
        if (header[0] != 0x50.toByte() || header[1] != 0x47.toByte()) fail("Invalid SUP header")
        val kind = header[10].toInt() and 0xff
        val size = ((header[11].toInt() and 0xff) shl 8) or (header[12].toInt() and 0xff)
        val body = ByteArray(size)
        input.readFully(body, 0, size)
        if (kind == PCS) beginDisplay(body, position)
        if (timeUs == C.TIME_UNSET) return Extractor.RESULT_CONTINUE
        if (display.size() + size + 3 > MAX_DISPLAY_BYTES) fail("SUP display set too large")
        display.write(header, 10, 3)
        display.write(body)
        if (kind == END) emitDisplay()
        return Extractor.RESULT_CONTINUE
    }

    private fun beginDisplay(body: ByteArray, position: Long) {
        if (body.size < 11) fail("Truncated SUP composition")
        display.reset()
        displayStart = position
        val pts = (2..5).fold(0L) { value, i -> (value shl 8) or (header[i].toLong() and 0xff) }
        if (lastPts - pts > (1L shl 31)) rollover += 1L shl 32
        lastPts = pts
        timeUs = (pts + rollover) * 1_000_000L / 90_000L
        if (!started && timeUs > seekTimeUs && lastDisplayUs != C.TIME_UNSET) {
            // Restore only the active display at the seek target, never replay historical cues.
            emit(parser.currentCues(), seekTimeUs)
            started = true
        }
        // Only epoch starts can be decoded without an earlier palette/object cache.
        if ((body[7].toInt() and 0x80) != 0) index.add(timeUs, displayStart)
    }

    private fun emitDisplay() {
        val bytes = display.toByteArray()
        val shouldOutput = timeUs >= seekTimeUs
        parser.consume(bytes, 0, bytes.size, if (shouldOutput) androidx.media3.common.util.Consumer {
            emit(it.cues, timeUs)
            started = true
        } else null)
        lastDisplayUs = timeUs
        display.reset()
        timeUs = C.TIME_UNSET
    }

    private fun emit(cues: List<Cue>, presentationUs: Long) {
        val bytes = encoder.encode(cues, C.TIME_UNSET)
        track.sampleData(ParsableByteArray(bytes), bytes.size)
        track.sampleMetadata(presentationUs, C.BUFFER_FLAG_KEY_FRAME, bytes.size, 0, null)
    }

    override fun seek(position: Long, timeUs: Long) {
        display.reset()
        this.timeUs = C.TIME_UNSET
        lastPts = 0L
        seekTimeUs = timeUs.coerceAtLeast(0)
        lastDisplayUs = C.TIME_UNSET
        started = false
        rollover = (timeUs.coerceAtLeast(0) * 90_000L / 1_000_000L) ushr 32 shl 32
        parser.reset()
        DebugLog.i("LumyPlayer", "sup seek pos=$position timeUs=$timeUs")
    }

    override fun release() = Unit

    private fun fail(message: String): Nothing = throw ParserException.createForMalformedContainer(message, null)

    private class SupSeekMap : SeekMap {
        private val epochs = TreeMap<Long, Long>().apply { put(0L, 0L) }
        @Synchronized fun add(timeUs: Long, position: Long) {
            if (epochs.size < MAX_INDEX_ENTRIES || epochs.containsKey(timeUs)) epochs[timeUs] = position
        }
        override fun isSeekable() = true
        override fun getDurationUs() = C.TIME_UNSET
        @Synchronized override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val epoch = epochs.floorEntry(timeUs) ?: epochs.firstEntry()
            return SeekMap.SeekPoints(SeekPoint(epoch.key, epoch.value))
        }
    }

    private companion object {
        const val PCS = 0x16
        const val END = 0x80
        const val MAX_DISPLAY_BYTES = 8 * 1024 * 1024
        const val MAX_INDEX_ENTRIES = 16_384
    }
}

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player.legacy

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.ProgressiveMediaExtractor
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DummyTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Tests use the same framework-free runner as the existing player instrumentation checks. */
class LegacyRmExtractorTest {
    fun runAll() {
        ordinaryInputReplaysPrefix()
        transportSeeksAndRetries()
        interruptionDoesNotOpenTransport()
        LegacyRmRecoveryTest().runAll()
    }

    private fun ordinaryInputReplaysPrefix() {
        val bytes = "ordinary-container-data".toByteArray()
        val trace = Trace(DataSource.Factory { ByteArrayDataSource(bytes) })
        val extracted = ByteArrayOutputStream()
        val parser = object : Extractor {
            override fun sniff(input: ExtractorInput): Boolean {
                val prefix = ByteArray(4)
                input.peekFully(prefix, 0, prefix.size)
                check(prefix.contentEquals(bytes.copyOf(4)))
                return true
            }
            override fun init(output: ExtractorOutput) = Unit
            override fun seek(position: Long, timeUs: Long) = Unit
            override fun release() = Unit
            override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
                val buffer = ByteArray(3)
                val count = input.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) return Extractor.RESULT_END_OF_INPUT
                extracted.write(buffer, 0, count)
                return Extractor.RESULT_CONTINUE
            }
        }
        val extractor = LegacyRmProgressiveExtractor.Factory(trace, ExtractorsFactory { arrayOf(parser) })
            .createProgressiveMediaExtractor(PlayerId.UNSET)
        val input = ByteArrayDataSource(bytes)
        input.open(DataSpec(TEST_URI))
        try {
            extractor.init(input, TEST_URI, emptyMap(), 0, bytes.size.toLong(), Capture())
            drainUntil(extractor) { extracted.size() == bytes.size }
            check(bytes.contentEquals(extracted.toByteArray()))
            check(extractor.currentInputPosition == bytes.size.toLong())
            check(trace.positions.isEmpty()) { "Ordinary media opened the native transport" }
        } finally {
            extractor.release()
            input.close()
        }
    }

    private fun transportSeeksAndRetries() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        var fail = false
        val trace = Trace(DataSource.Factory {
            val delegate = ByteArrayDataSource(bytes)
            object : DataSource by delegate {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (fail) {
                        fail = false
                        throw IOException("Injected read failure")
                    }
                    return delegate.read(buffer, offset, length)
                }
            }
        })
        val input = LegacyIoDataSource(trace, TEST_URI, bytes.size.toLong())
        val buffer = ByteArray(3)
        try {
            check(input.read(buffer, 0, 3) == 3 && buffer.contentEquals(bytes.copyOf(3)))
            fail = true
            check(runCatching { input.read(buffer, 0, 3) }.exceptionOrNull() is IOException)
            check(input.read(buffer, 0, 3) == 3 && buffer.contentEquals(bytes.copyOfRange(3, 6)))
            check(trace.positions == listOf(0L, 3L))
            check(input.seek(0, 0x10000) == bytes.size.toLong())
            check(input.seek(-2, 2) == 5L)
            check(input.read(buffer, 0, 2) == 2 && buffer[0] == bytes[5] && buffer[1] == bytes[6])
            check(input.read(buffer, 0, 1) == C.RESULT_END_OF_INPUT)
            check(input.seek(-1, 0) == -1L)
            check(input.seek(8, 0) == -1L)
        } finally {
            input.close()
            check(trace.active == 0) { "Transport leaked after retry/seek" }
        }
    }

    private fun interruptionDoesNotOpenTransport() {
        val trace = Trace(DataSource.Factory { ByteArrayDataSource(byteArrayOf(1)) })
        val input = LegacyIoDataSource(trace, TEST_URI, 1)
        Thread.currentThread().interrupt()
        try {
            check(runCatching { input.read(ByteArray(1), 0, 1) }.exceptionOrNull() is InterruptedIOException)
            check(trace.positions.isEmpty())
        } finally {
            Thread.interrupted()
            input.close()
        }
    }

    /** Supply a local or authenticated HTTP RM fixture; no movie is copied into test assets. */
    fun runFixture(factory: DataSource.Factory, uri: Uri, audioMime: String) {
        val trace = Trace(factory)
        val extractor = LegacyRmProgressiveExtractor.Factory(trace, DefaultExtractorsFactory())
            .createProgressiveMediaExtractor(PlayerId.UNSET)
        val upstream = trace.createDataSource()
        val length = upstream.open(DataSpec(uri))
        val capture = Capture()
        try {
            extractor.init(upstream, uri, upstream.responseHeaders, 0, length, capture)
            extractor.seek(0, 0)
            drainUntil(extractor) { capture.tracks.values.count { it.times.size >= 8 } >= 2 }
            val video = capture.tracks.values.first { it.format?.sampleMimeType == "video/realvideo" }
            val audio = capture.tracks.values.first { it.format?.sampleMimeType == audioMime }
            val initialAudio = checkNotNull(audio.firstPacket)
            check(video.format?.codecs == "rv40" && video.format?.initializationData?.single()?.size == 8)
            val packet = checkNotNull(video.packet)
            val slices = (packet[0].toInt() and 0xff) + 1
            check(packet.size > 1 + slices * 8) { "RealVideo slice table was removed" }
            val offsets = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            repeat(slices) { i ->
                check(offsets.getInt(1 + i * 8) == 1)
                check(offsets.getInt(5 + i * 8) in 0 until packet.size - 1 - slices * 8)
            }
            if (audioMime == "audio/cook") {
                val csd = checkNotNull(audio.format).initializationData
                check(csd.size == 2 && csd[0].size >= 8 && csd[1].size == 4)
                check(ByteBuffer.wrap(csd[1]).int > 0)
            }
            check(audio.times.distinct().size > 1) { "Audio timestamps did not advance" }
            check(audio.times.zipWithNext().all { (first, next) -> next > first && next - first < 100_000 }) {
                "Missing AAC/Cook subpacket timestamps were not derived from frame duration"
            }
            val map = checkNotNull(capture.map)
            check(map.isSeekable && map.durationUs > 0)
            val targetUs = minOf(5_000_000L, map.durationUs / 2)
            val seek = map.getSeekPoints(targetUs).first
            capture.tracks.values.forEach { it.times.clear() }
            extractor.seek(seek.position, seek.timeUs)
            drainUntil(extractor) { video.times.isNotEmpty() && audio.times.isNotEmpty() }
            check(video.times.first() >= 0 && video.times.first() <= targetUs)
            check(trace.positions.any { it > 0 }) { "Seek did not reopen a range" }
            check(extractor.currentInputPosition > 4) { "Native reads did not advance load-control position" }
            if (length > 1024 * 1024) check(trace.bytes < length) { "Probe buffered an entire movie" }
            capture.tracks.values.forEach { it.times.clear() }
            audio.firstPacket = null
            extractor.seek(0, 0)
            drainUntil(extractor) { video.times.isNotEmpty() && audio.times.size >= 8 }
            check(video.times.first() == 0L && audio.times.first() < 100_000) {
                "Backward seek retained the previous audio timestamp anchor"
            }
            check(initialAudio.contentEquals(checkNotNull(audio.firstPacket))) {
                "Backward seek retained a partially filled audio deinterleaving group"
            }
        } finally {
            extractor.release()
            upstream.close()
            check(trace.active == 0) { "Native DataSource was not closed" }
        }
    }

    private fun drainUntil(extractor: ProgressiveMediaExtractor, complete: () -> Boolean) {
        repeat(2000) {
            if (complete()) return
            check(extractor.read(PositionHolder()) != Extractor.RESULT_END_OF_INPUT) { "Premature EOF" }
        }
        error("Extractor did not reach the expected samples")
    }

    private class Trace(private val factory: DataSource.Factory) : DataSource.Factory {
        val positions = mutableListOf<Long>()
        var bytes = 0L
        var active = 0
        override fun createDataSource(): DataSource {
            val source = factory.createDataSource()
            return object : DataSource by source {
                private var open = false
                override fun open(dataSpec: DataSpec): Long {
                    positions.add(dataSpec.position)
                    open = true
                    active++
                    return source.open(dataSpec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    source.read(buffer, offset, length).also { if (it > 0) bytes += it }
                override fun close() {
                    if (open) active--
                    open = false
                    source.close()
                }
            }
        }
    }

    private class Capture : ExtractorOutput {
        var map: SeekMap? = null
        val tracks = mutableMapOf<Int, CapturedTrack>()
        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { CapturedTrack() }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) { map = seekMap }
    }

    private class CapturedTrack : TrackOutput by DummyTrackOutput() {
        var format: Format? = null
        var packet: ByteArray? = null
        var firstPacket: ByteArray? = null
        val times = mutableListOf<Long>()
        override fun format(format: Format) { this.format = format }
        override fun sampleData(data: ParsableByteArray, length: Int) {
            packet = ByteArray(length).also { data.readBytes(it, 0, length) }
            if (firstPacket == null) firstPacket = packet
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            check(timeUs != C.TIME_UNSET)
            times.add(timeUs)
        }
    }

    private companion object {
        val TEST_URI: Uri = Uri.parse("https://example.invalid/media")
    }
}

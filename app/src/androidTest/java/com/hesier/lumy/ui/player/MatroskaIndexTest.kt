@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DummyTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.type.AssRenderType
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

/** Synthetic containers exercise the production extractor, including its internal seek round trips. */
class MatroskaIndexTest {
    fun runAll() {
        for (lateTime in listOf(false, true)) {
            for (positionFirst in listOf(false, true)) {
                assertVideoIndex(fixture("direct", lateTime, positionFirst))
                assertVideoIndex(fixture("chain", lateTime, positionFirst))
            }
        }
        assertVideoIndex(fixture("far-chain"))
        assertVideoIndex(fixture("audio"))
        assertVideoIndex(fixture("inline"))
        for (kind in listOf("none", "empty", "loop", "bad", "inline-subtitle")) {
            val result = extract(fixture(kind))
            val point = result.map.getSeekPoints(TARGET_US).first
            check(result.map.isSeekable) { "$kind lost cluster fallback" }
            check(point.timeUs == TARGET_US) { "$kind unexpectedly published a Cues index: $point" }
            check(result.times == listOf(1_000_000L, 2_000_000L)) { "$kind lost samples: ${result.times}" }
        }
    }

    private fun assertVideoIndex(fixture: Fixture) {
        val result = extract(fixture)
        check(result.map.isSeekable) { "${fixture.label}: map is unseekable" }
        val point = result.map.getSeekPoints(TARGET_US).first
        check(point.timeUs == 1_000_000L && point.position == fixture.firstCluster) {
            "${fixture.label}: expected video Cue at 1000000/${fixture.firstCluster}, got $point"
        }
        val second = result.map.getSeekPoints(2_000_000L).first
        check(second.timeUs == 2_000_000L && second.position == fixture.secondCluster) {
            "${fixture.label}: second video Cue corrupted: $second"
        }
        check(result.times == listOf(1_000_000L, 2_000_000L)) {
            "${fixture.label}: internal seek damaged sample timestamps: ${result.times}"
        }
        val minimumSeeks = if (fixture.label.startsWith("inline/")) 0 else 2
        check(result.positions.size - 1 >= minimumSeeks) { "${fixture.label}: Cues seek/return was not exercised" }
        fixture.probeTarget?.let { target ->
            check(result.positions.take(3) == listOf(0L, target, 0L)) {
                "${fixture.label}: far SeekHead probe did not restore the original input: ${result.positions}"
            }
            check(result.positions.size >= 5) { "${fixture.label}: missing native Cues round trip" }
        }
    }

    private fun extract(fixture: Fixture): Result {
        val output = Capture()
        val ass = AssHandler(AssRenderType.OVERLAY_OPEN_GL, AssHandlerConfig())
        val extractor = ClusterSeekingExtractor(ProbingMatroskaExtractor(DefaultSubtitleParserFactory(), ass))
        extractor.init(output)
        val holder = PositionHolder()
        var reads = 0
        val positions = mutableListOf<Long>()
        var bytesRead = 0
        fun open(position: Long): DefaultExtractorInput {
            check(position in 0..fixture.bytes.size.toLong()) { "Invalid seek $position in ${fixture.label}" }
            positions.add(position)
            val stream = ByteArrayInputStream(fixture.bytes, position.toInt(), fixture.bytes.size - position.toInt())
            val reader = DataReader { buffer, offset, length ->
                stream.read(buffer, offset, length).also {
                    if (it > 0) bytesRead += it
                    check(bytesRead < MAX_BYTES_READ) { "Unbounded reads in ${fixture.label}" }
                }
            }
            return DefaultExtractorInput(reader, position, fixture.bytes.size.toLong())
        }
        var input = open(0)
        try {
            while (true) {
                check(++reads <= MAX_READS) { "Extractor did not terminate: ${fixture.label}" }
                when (extractor.read(input, holder)) {
                    Extractor.RESULT_END_OF_INPUT -> break
                    // Internal RESULT_SEEK reopens input without invoking the public seek callback.
                    Extractor.RESULT_SEEK -> input = open(holder.position)
                    Extractor.RESULT_CONTINUE -> Unit
                    else -> error("Unexpected extractor result")
                }
            }
        } finally {
            extractor.release()
        }
        return Result(checkNotNull(output.map), output.times, positions)
    }

    private data class Fixture(
        val label: String,
        val bytes: ByteArray,
        val firstCluster: Long,
        val secondCluster: Long,
        val probeTarget: Long? = null,
    )

    private data class Result(val map: SeekMap, val times: List<Long>, val positions: List<Long>)

    private fun fixture(kind: String, lateTime: Boolean = false, positionFirst: Boolean = false): Fixture {
        val ebml = master(0x1A45DFA3, text(0x4282, "matroska"), uint(0x4285, 2))
        val info = master(0x1549A966, uint(0x2AD7B1, 1_000_000), element(0x4489, ByteBuffer.allocate(8).putDouble(3000.0).array()))
        val tracks = tracks(kind == "audio")
        val inline = kind.startsWith("inline")
        val prefixSize = if (inline) cues(kind, 0, 0, lateTime, positionFirst).size else 0
        val first = cluster(1000)
        val second = cluster(2000)
        val padding = if (kind == "far-chain") element(0xEC, ByteArray(96 * 1024)) else byteArrayOf()
        val placeholder = if (kind == "none" || inline) byteArrayOf() else seekHead(0x114D9B74, 0)
        val firstOffset = info.size + tracks.size + placeholder.size + prefixSize
        val secondOffset = firstOffset + first.size
        val tailOffset = secondOffset + second.size + padding.size
        val tail = when (kind) {
            "chain", "far-chain" -> seekHead(0x1C53BB6B, tailOffset + placeholder.size)
            "loop" -> seekHead(0x114D9B74, tailOffset)
            "empty" -> master(0x114D9B74)
            else -> byteArrayOf()
        }
        val cuesOffset = tailOffset + tail.size
        val head = when (kind) {
            "none", "inline", "inline-subtitle" -> byteArrayOf()
            "direct", "audio" -> seekHead(0x1C53BB6B, cuesOffset)
            "bad" -> seekHead(0x114D9B74, 0x7FFF_FFFF)
            else -> seekHead(0x114D9B74, tailOffset)
        }
        check(head.size == placeholder.size)
        val index = if (kind in listOf("direct", "chain", "far-chain", "audio", "inline", "inline-subtitle"))
            cues(kind, firstOffset, secondOffset, lateTime, positionFirst) else byteArrayOf()
        val prefix = if (inline) index else byteArrayOf()
        val suffix = if (inline) byteArrayOf() else index
        val body = info + tracks + head + prefix + first + second + padding + tail + suffix
        val segment = element(0x18538067, body)
        val contentOffset = ebml.size + segment.size - body.size
        return Fixture("$kind/timeLast=$lateTime/positionFirst=$positionFirst", ebml + segment,
            (contentOffset + firstOffset).toLong(), (contentOffset + secondOffset).toLong(),
            if (kind == "far-chain") (contentOffset + tailOffset).toLong() else null)
    }

    private fun tracks(audioOnly: Boolean): ByteArray {
        if (audioOnly) return master(0x1654AE6B,
            master(0xAE, uint(0xD7, 1), uint(0x83, 2), text(0x86, "A_MPEG/L3")))
        return master(0x1654AE6B,
            master(0xAE, uint(0xD7, 1), uint(0x83, 1), text(0x86, "V_VP8"), master(0xE0, uint(0xB0, 16), uint(0xBA, 16))),
            master(0xAE, uint(0xD7, 2), uint(0x83, 17), text(0x86, "S_TEXT/UTF8")),
        )
    }

    private fun cues(kind: String, first: Int, second: Int, lateTime: Boolean, positionFirst: Boolean): ByteArray {
        if (kind == "inline-subtitle") return master(0x1C53BB6B,
            cue(1200, listOf(2 to second), lateTime, positionFirst))
        val firstTracks = if (kind == "audio") listOf(1 to first) else listOf(2 to second, 1 to first)
        val subtitle = if (kind == "audio") byteArrayOf() else cue(1200, listOf(2 to second), lateTime, positionFirst)
        return master(0x1C53BB6B, cue(1000, firstTracks, lateTime, positionFirst), subtitle,
            cue(2000, listOf(1 to second), lateTime, positionFirst))
    }

    private fun seekHead(id: Int, offset: Int) = master(0x114D9B74,
        master(0x4DBB, element(0x53AB, number(id.toLong(), 4)), uint(0x53AC, offset.toLong(), 4)))

    private fun cluster(time: Long) = master(0x1F43B675,
        uint(0xE7, time), element(0xEC, ByteArray(48)),
        element(0xA3, byteArrayOf(0x81.toByte(), 0, 0, 0x80.toByte(), 0x10, 0)),
    )

    private fun cue(time: Long, tracks: List<Pair<Int, Int>>, timeLast: Boolean, positionFirst: Boolean): ByteArray {
        val positions = tracks.map { (track, position) ->
            val trackElement = uint(0xF7, track.toLong())
            val positionElement = uint(0xF1, position.toLong(), 4)
            if (positionFirst) master(0xB7, positionElement, trackElement) else master(0xB7, trackElement, positionElement)
        }.fold(byteArrayOf()) { result, bytes -> result + bytes }
        val timestamp = uint(0xB3, time)
        return element(0xBB, if (timeLast) positions + timestamp else timestamp + positions)
    }

    private fun master(id: Int, vararg children: ByteArray) = element(id, children.fold(byteArrayOf()) { a, b -> a + b })
    private fun text(id: Int, value: String) = element(id, value.toByteArray(Charsets.UTF_8))
    private fun uint(id: Int, value: Long, width: Int = 4) = element(id, number(value, width))
    private fun number(value: Long, width: Int) = ByteArray(width) { index -> (value ushr ((width - index - 1) * 8)).toByte() }

    private fun element(id: Int, body: ByteArray): ByteArray {
        val idWidth = (4 downTo 1).first { id.toLong() ushr ((it - 1) * 8) != 0L }
        val sizeWidth = (1..4).first { body.size.toLong() < (1L shl (it * 7)) - 1 }
        val size = body.size.toLong() or (1L shl (sizeWidth * 7))
        return number(id.toLong(), idWidth) + number(size, sizeWidth) + body
    }

    private class Capture : ExtractorOutput {
        var map: SeekMap? = null
        val times = mutableListOf<Long>()
        private val dummy = DummyTrackOutput()
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by dummy {
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                if (id == 1) times.add(timeUs)
            }
        }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) { map = seekMap }
    }

    companion object {
        private const val TARGET_US = 1_300_000L
        private const val MAX_READS = 100
        private const val MAX_BYTES_READ = 1_000_000
    }
}

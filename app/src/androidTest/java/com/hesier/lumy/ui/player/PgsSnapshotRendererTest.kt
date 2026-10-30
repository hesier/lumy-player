@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Clock
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.text.TextOutput
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PgsSnapshotRendererTest {
    fun runAll() {
        withRenderer { test -> test.futurePausedAndBackwardClock() }
        withRenderer { test -> test.sameTimestampAndFinalState() }
        withRenderer { test -> test.seekRestoresPreviousActiveSnapshot() }
        withRenderer { test -> test.streamOffsetsAndReplacement() }
        withRenderer { test -> test.newPeriodClearsAtItsBoundary() }
        withRenderer { test -> test.catchUpSkipsExpiredSnapshots() }
        withRenderer { test -> test.fullDelayRangeKeepsSparseWindowPredecessor() }
        testCoalescedOutputCannotRestoreAnOldGeneration()
    }

    private class Test : AutoCloseable {
        val thread = HandlerThread("PgsRendererTestOutput").apply { start() }
        val groups = CopyOnWriteArrayList<CueGroup>()
        val renderer = PgsSnapshotRenderer(object : TextOutput {
            override fun onCues(cueGroup: CueGroup) { groups.add(cueGroup) }
        }, thread.looper).apply { init(0, PlayerId.UNSET, Clock.DEFAULT) }

        fun enable(source: Source, positionUs: Long = 0, offsetUs: Long = 0, final: Boolean = true) {
            renderer.enable(RendererConfiguration.DEFAULT, arrayOf(FORMAT), source, positionUs,
                false, true, offsetUs, offsetUs, MediaSource.MediaPeriodId(Any()))
            if (final) renderer.setCurrentStreamFinal()
        }

        fun await(positionUs: Long, color: Int?, ended: Boolean = false) {
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                renderer.render(positionUs, 0)
                barrier(thread)
                if (groups.isNotEmpty() && color(groups.last()) == color &&
                    (!ended || renderer.isEnded)) return
                Thread.sleep(1)
            }
            error("Expected color=$color ended=$ended at $positionUs, got ${groups.map(::color)}")
        }

        fun futurePausedAndBackwardClock() {
            enable(Source(listOf(1_000L to snapshot(Color.RED), 2_000L to snapshot(Color.BLACK),
                3_000L to PgsSnapshotCodec.encode(null))))
            renderer.render(0, 0)
            barrier(thread)
            check(groups.all { it.cues.isEmpty() }) { "A future snapshot appeared early" }
            check(renderer.state == Renderer.STATE_ENABLED && renderer.isReady)
            await(1_000, Color.RED)
            renderer.render(1_999, 0)
            barrier(thread)
            check(color(groups.last()) == Color.RED)
            await(2_000, Color.BLACK)
            await(1_500, Color.RED)
            await(3_000, null, ended = true)
            await(2_000, Color.BLACK)
            check(renderer.isReady)
        }

        fun sameTimestampAndFinalState() {
            enable(Source(listOf(1_000L to snapshot(Color.RED), 1_000L to snapshot(Color.BLACK))))
            await(1_000, Color.BLACK, ended = true)
            check(groups.none { color(it) == Color.RED }) { "A superseded same-PTS frame appeared" }
            renderer.render(5_000, 0)
            barrier(thread)
            check(color(groups.last()) == Color.BLACK) { "EOS cleared the final state" }
        }

        fun seekRestoresPreviousActiveSnapshot() {
            val source = Source(listOf(1_000L to snapshot(Color.RED), 2_000L to snapshot(Color.BLACK)))
            enable(source)
            await(2_000, Color.BLACK, ended = true)
            source.rewind()
            renderer.resetPosition(1_500)
            renderer.setCurrentStreamFinal()
            await(1_500, Color.RED)
            check(renderer.state == Renderer.STATE_ENABLED)
            source.rewind()
            renderer.resetPosition(500)
            renderer.setCurrentStreamFinal()
            renderer.render(500, 0)
            barrier(thread)
            check(groups.last().cues.isEmpty()) { "Seek retained an old frame before the first snapshot" }
        }

        fun streamOffsetsAndReplacement() {
            enable(Source(listOf(0L to snapshot(Color.RED))), offsetUs = 1_000_000, final = false)
            await(1_000_000, Color.RED)
            check(groups.last().presentationTimeUs == 0L)
            renderer.replaceStream(arrayOf(FORMAT), Source(listOf(0L to snapshot(Color.BLACK))),
                2_000_000, 2_000_000, MediaSource.MediaPeriodId(Any()))
            renderer.setCurrentStreamFinal()
            renderer.render(1_500_000, 0)
            barrier(thread)
            check(color(groups.last()) == Color.RED) { "Stream replacement discarded the active old cue" }
            await(2_000_000, Color.BLACK, ended = true)
            check(groups.last().presentationTimeUs == 0L)
        }

        fun newPeriodClearsAtItsBoundary() {
            enable(Source(listOf(0L to snapshot(Color.RED))), offsetUs = 1_000_000, final = false)
            await(1_000_000, Color.RED)
            renderer.replaceStream(arrayOf(FORMAT), Source(listOf(500_000L to snapshot(Color.BLACK))),
                2_000_000, 2_000_000, MediaSource.MediaPeriodId(Any()))
            renderer.setCurrentStreamFinal()
            renderer.render(1_999_999, 0)
            barrier(thread)
            check(color(groups.last()) == Color.RED) { "New period cleared the old state early" }
            await(2_000_000, null)
            check(!renderer.isEnded && groups.last().presentationTimeUs == 0L)
            await(2_500_000, Color.BLACK, ended = true)
            check(groups.last().presentationTimeUs == 500_000L)
        }

        fun catchUpSkipsExpiredSnapshots() {
            val samples = (0L until 100L).map { it to snapshot(if (it == 99L) Color.BLACK else Color.RED) }
            enable(Source(samples))
            await(100, Color.BLACK, ended = true)
            check(groups.none { color(it) == Color.RED }) { "Catch-up flashed an expired snapshot" }
        }

        fun fullDelayRangeKeepsSparseWindowPredecessor() {
            enable(Source(listOf(0L to snapshot(Color.WHITE), 9_000_000L to snapshot(Color.RED),
                11_000_000L to snapshot(Color.BLACK), 30_000_000L to snapshot(Color.GREEN))))
            val mediaPositionUs = 20_000_000L
            // A fixed media clock jumps back 20 seconds when delay changes from -10 to +10 seconds.
            await(mediaPositionUs - (-10_000_000L), Color.GREEN, ended = true)
            await(mediaPositionUs - 10_000_000L, Color.RED)
            check(groups.last().presentationTimeUs == 9_000_000L) { "Lost the sparse window predecessor" }
            await(11_000_000, Color.BLACK)
        }

        override fun close() {
            if (renderer.state == Renderer.STATE_STARTED) renderer.stop()
            if (renderer.state == Renderer.STATE_ENABLED) renderer.disable()
            renderer.release()
            barrier(thread)
            thread.quitSafely()
            thread.join()
        }
    }

    private class Source(private val samples: List<Pair<Long, ByteArray>>) : SampleStream {
        private var index = 0
        private var formatSent = false
        fun rewind() { index = 0; formatSent = false }
        override fun isReady() = true
        override fun maybeThrowError() = Unit
        override fun skipData(positionUs: Long) = 0
        override fun readData(holder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
            if (!formatSent || readFlags and SampleStream.FLAG_REQUIRE_FORMAT != 0) {
                holder.format = FORMAT
                formatSent = true
                return C.RESULT_FORMAT_READ
            }
            if (index == samples.size) {
                buffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
                return C.RESULT_BUFFER_READ
            }
            val (timeUs, bytes) = samples[index]
            buffer.timeUs = timeUs
            buffer.setFlags(C.BUFFER_FLAG_KEY_FRAME)
            if (readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA == 0) {
                buffer.ensureSpaceForWrite(bytes.size)
                checkNotNull(buffer.data).put(bytes)
            }
            if (readFlags and SampleStream.FLAG_PEEK == 0) index++
            return C.RESULT_BUFFER_READ
        }
    }

    private fun testCoalescedOutputCannotRestoreAnOldGeneration() {
        val thread = HandlerThread("PgsCoalescingTest").apply { start() }
        val block = CountDownLatch(1)
        val entered = CountDownLatch(1)
        Handler(thread.looper).post { entered.countDown(); check(block.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) }
        check(entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        val groups = CopyOnWriteArrayList<CueGroup>()
        val output = PgsSnapshotOutput(object : TextOutput {
            override fun onCues(cueGroup: CueGroup) { groups.add(cueGroup) }
        }, thread.looper)
        try {
            val generation = output.reset(0)
            val cue = PgsSnapshotCodec.decode(snapshot(Color.RED))
            repeat(1_000) { output.publish(CueGroup(cue, it.toLong()), generation) }
            output.reset(10_000)
            output.publish(CueGroup(cue, 20_000), generation)
            block.countDown()
            barrier(thread)
            check(groups.size == 1 && groups.single().cues.isEmpty()) { "Stale output survived reset: $groups" }
            check(groups.single().presentationTimeUs == 10_000L)
        } finally {
            block.countDown()
            thread.quitSafely()
            thread.join()
        }
    }

    private inline fun withRenderer(test: (Test) -> Unit) {
        Test().use(test)
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
        private val FORMAT = Format.Builder().setSampleMimeType(PgsSnapshotCodec.MIME_TYPE).build()

        private fun color(group: CueGroup) = group.cues.singleOrNull()?.bitmap?.getPixel(0, 0)

        private fun barrier(thread: HandlerThread) {
            val done = CountDownLatch(1)
            Handler(thread.looper).post { done.countDown() }
            check(done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "PGS output handler stalled" }
        }

        private fun snapshot(color: Int): ByteArray = PgsSnapshotCodec.encode(PgsSnapshotCodec.State(
            MultiObjectPgsParser.Composition(100, 100, 0,
                listOf(MultiObjectPgsParser.Placement(1, 0, 0, 0, null))),
            IntArray(256).apply { this[1] = color }, emptyMap(),
            listOf(PgsBitmapObjects.Encoded(1, 1, 1, byteArrayOf(1))),
        ))
    }
}

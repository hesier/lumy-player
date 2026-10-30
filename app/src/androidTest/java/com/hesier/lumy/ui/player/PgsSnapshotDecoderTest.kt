@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.media3.common.text.Cue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PgsSnapshotDecoderTest {
    fun runAll() {
        testBoundedRequestsAndGeneration()
        testDisplayedBitmapsSurviveFlushAndRelease()
        testBadSnapshotDoesNotStopLaterDecodes()
        testInputBounds()
    }

    private fun testBoundedRequestsAndGeneration() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val decoded = CopyOnWriteArrayList<Int>()
        val decoder = PgsSnapshotDecoder({}) { data ->
            check(Thread.currentThread().name == "LumyPgsSnapshot")
            val id = data.single().toInt()
            decoded.add(id)
            if (id == 1) {
                entered.countDown()
                check(unblock.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            }
            listOf(Cue.Builder().setText(id.toString()).build())
        }
        try {
            decoder.request(sample(1))
            check(entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            repeat(1_000) { decoder.request(sample(it + 2)) }
            decoder.flush()
            decoder.request(sample(4))
            unblock.countDown()
            val result = awaitOutput(decoder)
            check(result.sample.sequence == 4L && result.cues.single().text.toString() == "4")
            check(decoded == listOf(1, 4)) { "Worker did not coalesce pending requests: $decoded" }
            check(decoder.poll() == null)
        } finally {
            unblock.countDown()
            decoder.release()
        }
    }

    private fun testDisplayedBitmapsSurviveFlushAndRelease() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val decoder = PgsSnapshotDecoder({}) { listOf(Cue.Builder().setBitmap(bitmap).build()) }
        try {
            decoder.request(sample(1))
            val uiCues = awaitOutput(decoder).cues
            decoder.flush()
            decoder.request(sample(2))
            awaitOutput(decoder)
            decoder.release()
            check(!uiCues.single().bitmap!!.isRecycled) { "Worker recycled a Bitmap still held by the UI" }
            uiCues.single().bitmap!!.getPixel(0, 0)
        } finally {
            decoder.release()
        }
    }

    private fun testInputBounds() {
        val input = PgsSnapshotDecoderInputBuffer()
        input.ensureSpaceForWrite(1)
        try {
            input.ensureSpaceForWrite(PgsSnapshotCodec.MAX_SAMPLE_BYTES + 1)
            error("Oversized snapshot input was accepted")
        } catch (_: IllegalArgumentException) {
            check(input.data!!.capacity() == 1) { "Input grew before the bounds check" }
        }
    }

    private fun testBadSnapshotDoesNotStopLaterDecodes() {
        val decoder = PgsSnapshotDecoder({}) { data ->
            require(data.single().toInt() != 1) { "Invalid snapshot" }
            emptyList()
        }
        try {
            decoder.request(sample(1))
            check(awaitOutput(decoder).error is IllegalArgumentException)
            decoder.request(sample(2))
            check(awaitOutput(decoder).error == null)
        } finally {
            decoder.release()
        }
    }

    private fun awaitOutput(decoder: PgsSnapshotDecoder): PgsSnapshotOutputBuffer {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            decoder.poll()?.let { return it }
            Thread.sleep(1)
        }
        error("PGS worker did not produce output")
    }

    private fun sample(id: Int) = PgsSnapshotDecoder.Sample(id.toLong(), id.toLong(), 0, byteArrayOf(id.toByte()))

    companion object {
        private const val TIMEOUT_MS = 5_000L
    }
}

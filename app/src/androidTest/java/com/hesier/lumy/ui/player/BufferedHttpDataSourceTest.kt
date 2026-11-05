@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Deterministic stream fixtures test logical bytes separately from upstream prefetch notifications. */
class BufferedHttpDataSourceTest(private val instrumentation: Instrumentation) {
    fun runAll(): String {
        instrumentation.waitForIdleSync()
        byteReadsAndListeners()
        shortUnknownReads()
        reopenAndFailures()
        directLargeReads()
        metadataDelegation()
        return "Buffered HTTP: byte stream, bounded prefetch, listener accounting, short reads, EOF, Range reopen, failures, metadata and large-read bypass passed"
    }

    private fun byteReadsAndListeners() {
        val upstream = Fixture(pattern(20_003))
        val source = BufferedHttpDataSource(upstream)
        val events = Events()
        source.addTransferListener(events)
        check(source.open(spec()) == 20_003L)
        val first = ByteArray(1)
        check(source.read(first, 0, 0) == 0 && upstream.readCalls == 0)
        check(source.read(first, 0, 1) == 1 && first[0] == upstream.data[0])
        check(events.bytes == 8_192L && events.reads == 1) { "Listeners must count actual upstream prefetch" }
        val output = ByteArrayOutputStream().apply { write(first) }
        while (source.read(first, 0, 1) != C.RESULT_END_OF_INPUT) output.write(first)
        check(output.toByteArray().contentEquals(upstream.data))
        check(upstream.readCalls == 3 && events.reads == 3 && events.bytes == 20_003L)
        check(source.read(first, 0, 0) == 0 && source.read(first, 0, 1) == C.RESULT_END_OF_INPUT)
        source.close()
        check(events.initializes == 1 && events.starts == 1 && events.ends == 1)
    }

    private fun shortUnknownReads() {
        val upstream = Fixture(pattern(37)).apply { unknownLength = true; maxRead = 3 }
        val source = BufferedHttpDataSource(upstream)
        check(source.open(spec()) == C.LENGTH_UNSET.toLong())
        val output = ByteArrayOutputStream()
        val target = ByteArray(11)
        while (true) {
            val count = source.read(target, 2, 7)
            if (count == C.RESULT_END_OF_INPUT) break
            check(count in 1..3)
            output.write(target, 2, count)
        }
        check(output.toByteArray().contentEquals(upstream.data))
        check(upstream.readCalls == 14)
        val reads = upstream.readCalls
        check(source.read(target, 0, 1) == C.RESULT_END_OF_INPUT && upstream.readCalls == reads)
        source.close()
        val empty = BufferedHttpDataSource(Fixture(byteArrayOf()).apply { unknownLength = true })
        empty.open(spec())
        check(empty.read(target, 0, 1) == C.RESULT_END_OF_INPUT && empty.read(target, 0, 0) == 0)
        empty.close()
    }

    private fun reopenAndFailures() {
        val upstream = Fixture(pattern(25_000))
        val source = BufferedHttpDataSource(upstream)
        val one = ByteArray(1)
        source.open(spec())
        source.read(one, 0, 1)
        source.close()
        check(source.open(spec(12_000, 5)) == 5L)
        val range = ByteArray(20)
        check(source.read(range, 0, range.size) == 5)
        check(range.copyOf(5).contentEquals(upstream.data.copyOfRange(12_000, 12_005)))
        check(upstream.requested.last() == 5 && source.read(one, 0, 1) == C.RESULT_END_OF_INPUT)
        source.close()
        upstream.failOpen = true
        expectFailure { source.open(spec(20, 3)) }
        source.open(spec(40, 3))
        upstream.failRead = true
        expectFailure { source.read(one, 0, 1) }
        source.close()
        source.open(spec(60, 100))
        source.read(one, 0, 1)
        upstream.failClose = true
        expectFailure { source.close() }
        source.open(spec(90, 3))
        check(source.read(one, 0, 1) == 1 && one[0] == upstream.data[90])
        check(source.read(range, 0, range.size) == 2)
        check(range.copyOf(2).contentEquals(upstream.data.copyOfRange(91, 93)))
        source.close()
    }

    private fun directLargeReads() {
        val upstream = Fixture(pattern(30_000))
        val source = BufferedHttpDataSource(upstream)
        source.open(spec())
        val target = ByteArray(20_000)
        check(source.read(target, 2, 16_000) == 16_000)
        check(upstream.lastTarget === target && upstream.requested.single() == 16_000)
        check(target.copyOfRange(2, 16_002).contentEquals(upstream.data.copyOf(16_000)))
        val one = ByteArray(1)
        source.read(one, 0, 1)
        // A large read must first drain an older prefetched block before bypassing the buffer.
        val count = source.read(target, 0, 16_000)
        check(count == 8_191)
        check(target.copyOf(count).contentEquals(upstream.data.copyOfRange(16_001, 24_192)))
        check(source.read(target, 0, 16_000) == 5_808 && upstream.lastTarget === target)
        check(target.copyOf(5_808).contentEquals(upstream.data.copyOfRange(24_192, 30_000)))
        source.close()
    }

    private fun metadataDelegation() {
        val upstream = Fixture(pattern(20))
        val source = BufferedHttpDataSource(upstream)
        val requested = spec(4, 8).buildUpon().setHttpRequestHeaders(mapOf("X-Fixture" to "value")).build()
        source.open(requested)
        check(upstream.openedSpec === requested && source.getUri() == requested.uri)
        check(source.getResponseHeaders() === upstream.getResponseHeaders())
        source.close()
        check(source.getUri() == null)
    }

    private fun spec(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()): DataSpec =
        DataSpec.Builder().setUri("https://fixture.invalid/data").setPosition(position).setLength(length).build()

    private fun pattern(size: Int) = ByteArray(size) { ((it * 31 + it / 251) % 256).toByte() }

    private fun expectFailure(action: () -> Unit) {
        try { action() } catch (_: IOException) { return }
        error("Owned source did not propagate its I/O failure")
    }

    private class Fixture(val data: ByteArray) : DataSource {
        val requested = mutableListOf<Int>()
        var readCalls = 0
        var maxRead = Int.MAX_VALUE
        var unknownLength = false
        var failOpen = false
        var failRead = false
        var failClose = false
        var lastTarget: ByteArray? = null
        var openedSpec: DataSpec? = null
        private val listeners = mutableListOf<TransferListener>()
        private val headers = mapOf("Content-Type" to listOf("video/x-matroska"))
        private var position = 0
        private var end = 0

        override fun addTransferListener(listener: TransferListener) { listeners.add(listener) }
        override fun getUri(): Uri? = openedSpec?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = headers

        override fun open(dataSpec: DataSpec): Long {
            if (failOpen) { failOpen = false; throw IOException("fixture open") }
            openedSpec = dataSpec
            position = dataSpec.position.toInt()
            end = if (dataSpec.length < 0) data.size else minOf(data.size.toLong(), position + dataSpec.length).toInt()
            listeners.forEach { it.onTransferInitializing(this, dataSpec, true) }
            listeners.forEach { it.onTransferStart(this, dataSpec, true) }
            return if (unknownLength) C.LENGTH_UNSET.toLong() else (end - position).toLong()
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            readCalls++
            if (failRead) { failRead = false; throw IOException("fixture read") }
            requested.add(length)
            lastTarget = target
            if (length == 0) return 0
            if (position == end) return C.RESULT_END_OF_INPUT
            val count = minOf(length, maxRead, end - position)
            System.arraycopy(data, position, target, offset, count)
            position += count
            listeners.forEach { it.onBytesTransferred(this, checkNotNull(openedSpec), true, count) }
            return count
        }

        override fun close() {
            openedSpec?.let { spec -> listeners.forEach { it.onTransferEnd(this, spec, true) } }
            openedSpec = null
            if (failClose) { failClose = false; throw IOException("fixture close") }
        }
    }

    private class Events : TransferListener {
        var bytes = 0L
        var reads = 0
        var initializes = 0
        var starts = 0
        var ends = 0
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { initializes++ }
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { starts++ }
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            bytes += bytesTransferred; reads++
        }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { ends++ }
    }
}

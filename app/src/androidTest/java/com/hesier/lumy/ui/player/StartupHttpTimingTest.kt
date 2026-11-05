@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.os.SystemClock
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Owned loopback HTTP verifies the actual Media3 enqueue path without a server identity or media. */
class StartupHttpTimingTest(private val instrumentation: Instrumentation) {
    fun runAll(): String {
        instrumentation.waitForIdleSync()
        queuedMediaRead()
        staleTransfersAndBudget()
        return "Startup HTTP timing: queued Media3 request, numeric Range, first read, privacy, stale callbacks and 12-request budget passed"
    }

    private fun queuedMediaRead() {
        val lines = CopyOnWriteArrayList<String>()
        val timing = PlaybackStartupTiming(output = lines::add)
        val transfer = PlaybackStartupTimingTransfer(timing)
        val attempt = timing.begin()
        val dispatcher = Dispatcher().apply { maxRequests = 1; maxRequestsPerHost = 1 }
        val client = OkHttpClient.Builder().dispatcher(dispatcher).callTimeout(10, TimeUnit.SECONDS).build()
        val loader = Executors.newSingleThreadExecutor()
        val blockerDone = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<String>()
        val server = LocalServer()
        try {
            client.newCall(Request.Builder().url(server.url).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    failures.add(e.javaClass.simpleName)
                    blockerDone.countDown()
                }
                override fun onResponse(call: Call, response: Response) {
                    try { response.use { it.body?.bytes() } }
                    catch (failure: Exception) { failures.add(failure.javaClass.simpleName) }
                    finally { blockerDone.countDown() }
                }
            })
            check(server.firstAccepted.await(5, TimeUnit.SECONDS)) { "Owned blocker did not reach the loopback server" }
            val completed = loader.submit<Int> {
                val source = OkHttpDataSource.Factory(transfer.callFactory(client)).setTransferListener(transfer)
                    .createDataSource()
                val spec = DataSpec.Builder().setUri(server.url).setKey(attempt.key).setLength(8)
                    .setHttpRequestHeaders(mapOf("Authorization" to "Bearer private-auth-value")).build()
                try {
                    source.open(spec)
                    val bytes = ByteArray(8)
                    var count = 0
                    while (count < bytes.size) {
                        val read = source.read(bytes, count, bytes.size - count)
                        if (read < 0) break
                        count += read
                    }
                    count
                } finally { source.close() }
            }
            await("Media3 request queued behind the owned blocker") { dispatcher.queuedCallsCount() == 1 }
            Thread.sleep(QUEUE_HOLD_MS)
            server.releaseFirst.countDown()
            check(completed.get(6, TimeUnit.SECONDS) == 8)
            check(blockerDone.await(5, TimeUnit.SECONDS) && failures.isEmpty()) { "Owned blocker failed" }
            verifyQueuedTrace(lines, server)
        } finally {
            server.close()
            loader.shutdownNow()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private fun verifyQueuedTrace(lines: List<String>, server: LocalServer) {
        val headers = lines.single { "phase=headers" in it }
        val fields = numericFields(headers)
        check(checkNotNull(fields["queueMs"]) >= QUEUE_HOLD_MS - QUEUE_TOLERANCE_MS) {
            "Queue time omitted the deliberately blocked dispatch interval"
        }
        check(fields["status"] == 206L && fields["declaredBytes"] == 8L)
        check(fields["rangeStart"] == 0L && fields["rangeEnd"] == 7L)
        check(server.rangeSeen && server.authorizationSeen) { "The measured call lost its Range or auth header" }
        check(lines.count { "phase=first_read" in it } == 1)
        check(lines.count { "stage=HTTP_FIRST_READ" in it } == 1)
        check(lines.single { "stage=HTTP_TRANSFER_END" in it }.contains("bytes=8"))
        check(lines.none { line -> PRIVATE_VALUES.any(line::contains) }) { "HTTP trace exposed private fixture text" }
        check(server.failure == null) { "Owned loopback server failed" }
    }

    private fun staleTransfersAndBudget() {
        val lines = mutableListOf<String>()
        val timing = PlaybackStartupTiming(output = lines::add)
        val client = OkHttpClient()
        try {
            for (replace in listOf(false, true)) {
                val transfer = PlaybackStartupTimingTransfer(timing)
                val old = timing.begin()
                val source = ByteArrayDataSource(byteArrayOf(1))
                val spec = DataSpec.Builder().setUri(PRIVATE_URL).setKey(old.key).setLength(8).build()
                transfer.onTransferInitializing(source, spec, true)
                val call = transfer.callFactory(client).newCall(Request.Builder().url(PRIVATE_URL).build())
                val trace = checkNotNull(call.request().tag(PlaybackStartupHttp.Trace::class.java))
                if (replace) timing.begin() else timing.end(PlaybackStartupTiming.End.CANCELLED)
                val count = lines.size
                trace.dispatched()
                trace.responseHeadersEnd(call, fakeResponse(call))
                trace.callFailed(call, IOException("private-failure-value"))
                transfer.onTransferStart(source, spec, true)
                transfer.onBytesTransferred(source, spec, true, 8)
                transfer.onTransferEnd(source, spec, true)
                check(lines.size == count) { "A stale network event entered the current startup trace" }
            }
            val http = PlaybackStartupHttp(timing)
            val attempt = timing.begin()
            val spec = DataSpec.Builder().setUri(PRIVATE_URL).setKey(attempt.key).build()
            repeat(12) { check(http.initializing(attempt, spec) != null) }
            check(http.initializing(attempt, spec) == null) { "HTTP trace exceeded its request budget" }
            val next = timing.begin()
            check(http.initializing(next, spec) != null) { "A new attempt did not receive a fresh budget" }
            timing.mark(next, PlaybackStartupTiming.Stage.PREPARE)
            timing.renderer(next, PlaybackStartupTiming.Stage.FIRST_FRAME, SystemClock.elapsedRealtime())
            check(http.initializing(next, spec) == null) { "New HTTP traces were created after the first frame" }
            http.clearPending()
            check(lines.none { line -> PRIVATE_VALUES.any(line::contains) })
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private fun fakeResponse(call: Call): Response = Response.Builder().request(call.request())
        .protocol(Protocol.HTTP_1_1).code(206).message("private-response-value")
        .header("Content-Length", "8").header("Content-Range", "bytes 0-7/8").build()

    private fun numericFields(line: String): Map<String, Long> =
        Regex("([A-Za-z]+)=(-?\\d+)").findAll(line).associate { it.groupValues[1] to it.groupValues[2].toLong() }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        error("Timed out: $label")
    }

    private class LocalServer : AutoCloseable {
        private val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/private-title?token=private-token-value"
        val firstAccepted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        @Volatile var rangeSeen = false
        @Volatile var authorizationSeen = false
        @Volatile var failure: String? = null
        @Volatile private var socket: Socket? = null
        private val thread = Thread({ serve() }, "StartupHttpLoopback").apply { isDaemon = true; start() }

        private fun serve() {
            try {
                repeat(2) { index ->
                    server.accept().use { accepted ->
                        socket = accepted
                        accepted.soTimeout = 5_000
                        val reader = accepted.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val requestHeaders = mutableListOf<String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            requestHeaders.add(line)
                        }
                        if (index == 0) {
                            firstAccepted.countDown()
                            check(releaseFirst.await(5, TimeUnit.SECONDS))
                        } else {
                            rangeSeen = requestHeaders.any { it.equals("Range: bytes=0-7", ignoreCase = true) }
                            authorizationSeen = requestHeaders.any { it == "Authorization: Bearer private-auth-value" }
                        }
                        val status = if (index == 0) "200 OK" else "206 Partial Content"
                        val range = if (index == 0) "" else "Content-Range: bytes 0-7/8\r\n"
                        val response = "HTTP/1.1 $status\r\nContent-Length: 8\r\n${range}Connection: close\r\n\r\nPRIVATE!"
                        accepted.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                        accepted.getOutputStream().flush()
                    }
                }
            } catch (problem: Exception) {
                if (!server.isClosed) failure = problem.javaClass.simpleName
            } finally { socket = null }
        }

        override fun close() {
            releaseFirst.countDown()
            server.close()
            socket?.close()
            thread.join(2_000)
        }
    }

    private companion object {
        const val QUEUE_HOLD_MS = 250L
        const val QUEUE_TOLERANCE_MS = 30L
        const val PRIVATE_URL = "http://127.0.0.1/private-title?token=private-token-value"
        val PRIVATE_VALUES = listOf("127.0.0.1", "private-title", "private-token-value", "private-auth-value",
            "private-response-value", "private-failure-value", "PRIVATE!", "Authorization", "http://")
    }
}

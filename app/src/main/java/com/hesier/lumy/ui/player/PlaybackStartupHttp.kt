@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.os.SystemClock
import androidx.media3.datasource.DataSpec
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/** A bounded debug trace. Media3 creates the call synchronously after transferInitializing. */
internal class PlaybackStartupHttp(private val timing: PlaybackStartupTiming) {
    private val pending = ThreadLocal<Trace>()

    fun initializing(attempt: PlaybackStartupTiming.Attempt, spec: DataSpec): Trace? {
        clearPending()
        val number = timing.nextNetwork(attempt) ?: return null
        return Trace(attempt, number, position = spec.position, length = spec.length).also(pending::set)
    }

    fun clearPending() = pending.remove()

    fun callFactory(http: OkHttpClient): Call.Factory {
        val measured = http.newBuilder().addInterceptor { chain ->
            val trace = chain.request().tag(Trace::class.java)
            trace?.dispatched()
            val response = chain.proceed(chain.request())
            val body = response.body
            if (trace == null || body == null) response
            else response.newBuilder().body(measuredBody(body, trace)).build()
        }.eventListenerFactory { call ->
            call.request().tag(Trace::class.java) ?: http.eventListenerFactory.create(call)
        }.build()
        return Call.Factory { request ->
            val trace = pending.get()
            clearPending()
            if (trace == null) http.newCall(request) else {
                trace.queued(http.dispatcher.runningCallsCount(), http.dispatcher.queuedCallsCount())
                measured.newCall(request.newBuilder().tag(Trace::class.java, trace).build())
            }
        }
    }

    private fun measuredBody(body: ResponseBody, trace: Trace): ResponseBody {
        val source = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val started = SystemClock.elapsedRealtimeNanos()
                var count = 0L
                try { return super.read(sink, byteCount).also { count = it.coerceAtLeast(0) } }
                finally { trace.readCall(SystemClock.elapsedRealtimeNanos() - started, count) }
            }

            override fun close() {
                val started = SystemClock.elapsedRealtimeNanos()
                try { super.close() } finally { trace.closed(SystemClock.elapsedRealtimeNanos() - started) }
            }
        }.buffer()
        return object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = source
        }
    }

    internal inner class Trace(
        private val attempt: PlaybackStartupTiming.Attempt,
        private val number: Int,
        private val position: Long,
        private val length: Long,
    ) : EventListener() {
        private val started = SystemClock.elapsedRealtime()
        private var dispatchMs = -1L
        private var callStartedMs = -1L
        private var requestSentMs = -1L
        private var openedMs = -1L
        private var running = 0
        private var queued = 0
        private var dnsStarted = 0L
        private var dnsMs = -1L
        private var connectStarted = 0L
        private var connectMs = -1L
        private var tlsStarted = 0L
        private var tlsMs = -1L
        private var acquiredMs = -1L
        private var readNs = 0L
        private var closedReported = false
        private var bodyBytes = 0L
        private var reads = 0

        @Synchronized fun queued(running: Int, queued: Int) { this.running = running; this.queued = queued }
        @Synchronized fun dispatched() { dispatchMs = elapsed() }
        @Synchronized fun opened() { openedMs = elapsed() }
        @Synchronized fun readCall(ns: Long, bytes: Long) { readNs += ns; bodyBytes += bytes; reads++ }
        @Synchronized fun closed(ns: Long) {
            if (closedReported) return
            closedReported = true
            report("phase=body_closed elapsedMs=${elapsed()} closeMs=${ns / 1_000_000}")
        }

        @Synchronized fun ended(delivered: Long) {
            // Refills exclude per-byte DataSource callbacks and extractor work between refills.
            // Media3 sends transferEnded before closing its response body; report those separately.
            report("phase=transfer_end elapsedMs=${elapsed()} refillReadMs=${readNs / 1_000_000} " +
                "reads=$reads bodyBytesRead=$bodyBytes deliveredBytes=$delivered")
        }
        @Synchronized override fun callStart(call: Call) { callStartedMs = elapsed() }
        @Synchronized override fun requestHeadersEnd(call: Call, request: okhttp3.Request) { requestSentMs = elapsed() }

        @Synchronized fun firstRead(bytes: Int) {
            val now = elapsed()
            report("phase=first_read openMs=$openedMs firstReadMs=$now " +
                "afterOpenMs=${if (openedMs < 0) -1 else now - openedMs} bytes=$bytes")
        }

        @Synchronized override fun dnsStart(call: Call, domainName: String) { dnsStarted = elapsed() }
        @Synchronized override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            dnsMs = elapsed() - dnsStarted
        }

        @Synchronized override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connectStarted = elapsed()
        }

        @Synchronized override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy,
            protocol: okhttp3.Protocol?) { connectMs = elapsed() - connectStarted }

        @Synchronized override fun secureConnectStart(call: Call) { tlsStarted = elapsed() }
        @Synchronized override fun secureConnectEnd(call: Call, handshake: Handshake?) { tlsMs = elapsed() - tlsStarted }
        @Synchronized override fun connectionAcquired(call: Call, connection: Connection) { acquiredMs = elapsed() }

        @Synchronized override fun responseHeadersEnd(call: Call, response: Response) {
            val range = CONTENT_RANGE.matchEntire(response.header("Content-Range").orEmpty())
            val rangeStart = range?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            val rangeEnd = range?.groupValues?.get(2)?.toLongOrNull() ?: -1L
            val declared = response.header("Content-Length")?.toLongOrNull() ?: -1L
            val queueMs = if (callStartedMs < 0 || dispatchMs < 0) -1 else dispatchMs - callStartedMs
            val responseWaitMs = if (requestSentMs < 0) -1 else elapsed() - requestSentMs
            report("phase=headers queueMs=$queueMs dnsMs=$dnsMs connectMs=$connectMs tlsMs=$tlsMs " +
                "acquiredMs=$acquiredMs headersMs=${elapsed()} running=$running queued=$queued " +
                "responseWaitMs=$responseWaitMs " +
                "status=${response.code} declaredBytes=$declared rangeStart=$rangeStart rangeEnd=$rangeEnd")
        }

        @Synchronized override fun callFailed(call: Call, ioe: IOException) {
            report("phase=failed elapsedMs=${elapsed()}")
        }

        private fun elapsed() = SystemClock.elapsedRealtime() - started
        private fun report(fields: String) =
            timing.network(attempt, number, "position=$position length=$length $fields")
    }

    private companion object {
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(?:\\d+|\\*)")
    }
}

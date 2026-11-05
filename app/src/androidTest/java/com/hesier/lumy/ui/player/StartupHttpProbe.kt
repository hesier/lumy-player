package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.os.SystemClock
import com.hesier.lumy.LumyApp
import com.hesier.lumy.core.PlaybackMode
import com.hesier.lumy.core.PlaybackRequest
import com.hesier.lumy.util.DebugLog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Anonymous logging around bounded real HTTP reads; never logs request identities. */
class StartupHttpProbe(private val instrumentation: Instrumentation) {
    private val context get() = instrumentation.targetContext
    private val report get() = File(context.cacheDir, "startup-http-probe.jsonl")
    private lateinit var http: OkHttpClient
    private lateinit var url: String
    private var headers: Map<String, String> = emptyMap()
    private var sequence = 0

    fun run(itemId: String): String {
        return try {
            if (!awaitApplication()) return "Startup HTTP probe: application_not_ready"
            if (itemId.isBlank()) return "Startup HTTP probe: invalid_input"
            prepare(itemId)
            report.writeText("")
            emit(JSONObject().put("event", "begin").put("mode", "synchronous_execute_no_async_dispatcher_queue")
                .put("readLimitBytes", READ_LIMIT).put("callTimeoutMs", CALL_TIMEOUT_MS))
            var total: Long? = null
            for (kind in listOf("no_range_1", "no_range_2", "head_range_1", "head_range_2")) {
                val range = if (kind.startsWith("head")) "bytes=0-${READ_LIMIT - 1}" else null
                total = probe(kind, range) ?: total
            }
            if (total != null && total > 0) {
                probe("tail_range", "bytes=${(total - READ_LIMIT).coerceAtLeast(0)}-${total - 1}")
            } else emit(JSONObject().put("event", "tail_skipped").put("reason", "header_length_unavailable"))
            "Startup HTTP probe: requests=$sequence; anonymous results in cache/startup-http-probe.jsonl; " +
                "synchronous execute bypasses Media3 asynchronous dispatcher queuing"
        } catch (failure: Exception) {
            val type = failure.javaClass.simpleName
            runCatching { emit(JSONObject().put("event", "setup_failed").put("type", type)) }
            "Startup HTTP probe: setup_failed type=$type"
        }
    }

    private fun awaitApplication(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync {
                ready = runCatching {
                    LumyApp.instance.plex.http
                    LumyApp.instance.currentEntry != null && LumyApp.instance.currentServer() != null
                }.getOrDefault(false)
            }
            if (ready) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun prepare(itemId: String) = runBlocking {
        withTimeout(30_000) {
            val app = LumyApp.instance
            http = app.plex.http
            val server = checkNotNull(app.currentServer())
            val detail = server.detail(itemId)
            val version = detail.versions.firstOrNull { it.parts.size == 1 }
                ?: throw IllegalStateException()
            val request = PlaybackRequest(detail.item, version, PlaybackMode.Direct, 0, UUID.randomUUID().toString())
            val source = server.playbackSource(request)
            check(!source.isHls && source.urls.size == 1)
            url = source.urls.single()
            headers = server.playbackHeaders()
        }
    }

    private fun probe(kind: String, range: String?): Long? {
        val metrics = Metrics(++sequence, kind)
        val client = http.newBuilder().callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .eventListener(metrics).build()
        var total: Long? = null
        try {
            val request = Request.Builder().url(url).apply {
                headers.forEach { (name, value) -> header(name, value) }
                removeHeader("Range")
                if (range != null) header("Range", range)
                header("Accept-Encoding", "identity")
            }.build()
            metrics.row.put("queuedAsyncAtStart", client.dispatcher.queuedCallsCount())
                .put("runningCallsAtStart", client.dispatcher.runningCallsCount())
            metrics.mark("executeBeginMs")
            client.newCall(request).execute().use { response ->
                metrics.mark("executeReturnedMs")
                total = responseMetadata(response, metrics.row)
                if (response.isSuccessful) readBody(response, metrics)
            }
            metrics.row.put("outcome", "response_closed")
        } catch (failure: Exception) {
            metrics.row.put("outcome", "failed").put("type", failure.javaClass.simpleName)
        }
        metrics.mark("closedMs")
        emit(metrics.row)
        return total
    }

    private fun responseMetadata(response: Response, row: JSONObject): Long? {
        val length = response.body?.contentLength() ?: -1
        row.put("status", response.code).put("contentLength", length)
            .put("contentRangePresent", response.header("Content-Range") != null)
        val parsed = response.header("Content-Range")?.let(CONTENT_RANGE::matchEntire)
        var total: Long? = null
        if (parsed != null) {
            val values = parsed.groupValues.drop(1).map(String::toLongOrNull)
            val start = values[0]
            val end = values[1]
            row.put("contentRangeValid", start != null && end != null && start <= end &&
                (values[2] == null || end < values[2]!!))
            values[0]?.let { row.put("rangeStart", it) }
            values[1]?.let { row.put("rangeEnd", it) }
            total = values[2]?.takeIf { it > 0 }
            total?.let { row.put("rangeTotal", it) }
        } else row.put("contentRangeValid", false)
        return total ?: length.takeIf { response.code == 200 && it > 0 }
    }

    private fun readBody(response: Response, metrics: Metrics) {
        val stream = response.body?.byteStream() ?: return
        val buffer = ByteArray(READ_LIMIT)
        var bytes = 0
        while (bytes < READ_LIMIT) {
            val count = stream.read(buffer, bytes, READ_LIMIT - bytes)
            if (count < 0) break
            if (count == 0) continue
            if (bytes == 0) metrics.mark("firstPositiveBodyReadMs")
            bytes += count
        }
        metrics.row.put("bodyBytesRead", bytes)
        metrics.mark("readLimitOrEofMs")
    }

    private fun emit(row: JSONObject) {
        val line = row.toString()
        report.appendText(line + "\n")
        DebugLog.i("LumyHttpProbe", line)
    }

    private class Metrics(sequence: Int, kind: String) : EventListener() {
        val row = JSONObject().put("event", "request").put("sequence", sequence).put("case", kind)
            .put("dispatchMode", "sync_execute_no_async_queue")
        private val startNs = SystemClock.elapsedRealtimeNanos()
        private var connects = 0

        @Synchronized fun mark(name: String) { row.put(name, (SystemClock.elapsedRealtimeNanos() - startNs) / 1e6) }
        override fun callStart(call: Call) = mark("callStartMs")
        override fun dnsStart(call: Call, domainName: String) = mark("dnsStartMs")
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = mark("dnsEndMs")
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connects++
            mark("connectStartMs")
        }
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) =
            mark("connectEndMs")
        override fun secureConnectStart(call: Call) = mark("tlsStartMs")
        override fun secureConnectEnd(call: Call, handshake: Handshake?) = mark("tlsEndMs")
        override fun connectionAcquired(call: Call, connection: Connection) {
            row.put("connectAttempts", connects).put("acquiredWithoutConnectEvent", connects == 0)
            mark("connectionAcquiredMs")
        }
        override fun requestHeadersStart(call: Call) = mark("requestHeadersStartMs")
        override fun requestHeadersEnd(call: Call, request: Request) = mark("requestHeadersEndMs")
        override fun responseHeadersStart(call: Call) = mark("responseHeadersStartMs")
        override fun responseHeadersEnd(call: Call, response: Response) = mark("responseHeadersEndMs")
        override fun responseBodyStart(call: Call) = mark("responseBodyStartMs")
        override fun responseBodyEnd(call: Call, byteCount: Long) {
            row.put("eventBodyByteCount", byteCount)
            mark("responseBodyEndMs")
        }
        override fun callEnd(call: Call) = mark("callEndMs")
        override fun callFailed(call: Call, ioe: IOException) {
            row.put("callFailureType", ioe.javaClass.simpleName)
            mark("callFailedMs")
        }
    }

    private companion object {
        const val READ_LIMIT = 8 * 1024
        const val CALL_TIMEOUT_MS = 20_000L
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
    }
}

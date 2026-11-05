package com.hesier.lumy.data

import android.os.SystemClock
import com.hesier.lumy.BuildConfig
import com.hesier.lumy.net.danmaku.DanmakuException
import com.hesier.lumy.util.DebugLog
import kotlinx.coroutines.CancellationException
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.atomic.AtomicBoolean

internal enum class DanmakuStage { LOAD, IDENTIFY, COMMENTS, OVERLAY }

/** Fixed categories only: endpoint paths, credentials, media identities and response text stay private. */
internal object DanmakuDiagnostics : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!BuildConfig.DEBUG) return chain.proceed(chain.request())
        val path = chain.request().url.pathSegments
        val api = path.zipWithNext().indexOfLast { it.first == "api" && it.second == "v2" }
        val route = when (path.getOrNull(if (api < 0) -1 else api + 2)) {
            "match" -> "match"
            "search" -> "search"
            "bangumi" -> "details"
            "comment" -> "comment"
            else -> "other"
        }
        val trace = RequestTrace(route)
        val response = try { chain.proceed(chain.request()) }
        catch (error: Exception) {
            trace.report(0, "response_error")
            throw error
        }
        trace.received(response.code, response.body?.contentLength() ?: -1)
        val body = response.body ?: return response.also { trace.report(0, "no_body") }
        return response.newBuilder().body(measured(body) { bytes, outcome ->
            trace.report(bytes, outcome)
        }).build()
    }

    private fun measured(body: ResponseBody, finished: (Long, String) -> Unit): ResponseBody {
        val reported = AtomicBoolean()
        var bytes = 0L
        fun report(outcome: String) { if (reported.compareAndSet(false, true)) finished(bytes, outcome) }
        val source = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long = try {
                super.read(sink, byteCount).also { if (it < 0) report("eof") else bytes += it }
            } catch (error: Exception) { report("read_error"); throw error }

            override fun close() {
                try { super.close() } finally { report("closed") }
            }
        }.buffer()
        return object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = source
        }
    }

    private class RequestTrace(private val route: String) {
        private val started = SystemClock.elapsedRealtime()
        private var status = 0
        private var declaredBytes = -1L
        private var responseHeadersMs = -1L

        fun received(status: Int, declaredBytes: Long) {
            this.status = status
            this.declaredBytes = declaredBytes
            responseHeadersMs = SystemClock.elapsedRealtime() - started
        }

        fun report(bytes: Long, outcome: String) {
            // Body reads may include buffered prefetch and transparent decompression, never wire traffic.
            DebugLog.i("LumyDanmaku", "request route=$route status=$status declaredBodyBytes=$declaredBytes " +
                "bodyBytesRead=$bytes bytesKind=body_not_wire responseHeadersMs=$responseHeadersMs " +
                "elapsedMs=${SystemClock.elapsedRealtime() - started} outcome=$outcome")
        }
    }

    fun oversized(route: String) {
        if (BuildConfig.DEBUG) DebugLog.i("LumyDanmaku", "fallback route=$route reason=TOO_LARGE action=manual_or_next_candidate")
    }

    suspend fun <T> stage(stage: DanmakuStage, action: suspend () -> T): T {
        if (!BuildConfig.DEBUG) return action()
        val started = SystemClock.elapsedRealtime()
        var outcome = "complete"
        try {
            return action().also {
                outcome = when (it) {
                    is DanmakuLoad.Failed -> "failed_${it.reason}"
                    is DanmakuLoad.NeedsMatch -> "needs_match_${it.reason}"
                    is DanmakuLoad.Loaded -> "loaded"
                    is DanmakuLoad.Empty -> "empty"
                    is DanmakuDecision -> if (it.selected != null) "selected" else "pending_${it.reason}"
                    else -> "complete"
                }
            }
        } catch (cancelled: CancellationException) { outcome = "cancelled"; throw cancelled }
        catch (error: DanmakuException) { outcome = "failed_${error.reason}"; throw error }
        catch (error: Exception) { outcome = "failed"; throw error }
        finally {
            DebugLog.i("LumyDanmaku", "stage=$stage elapsedMs=${SystemClock.elapsedRealtime() - started} outcome=$outcome")
        }
    }
}

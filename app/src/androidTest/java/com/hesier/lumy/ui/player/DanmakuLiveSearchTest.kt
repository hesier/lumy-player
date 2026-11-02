package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import com.hesier.lumy.data.DanmakuServices
import com.hesier.lumy.net.danmaku.DanmakuApi
import com.hesier.lumy.net.danmaku.DanmakuException
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

/** Explicit network diagnostic; never logs endpoints, keywords, headers or response contents. */
internal class DanmakuLiveSearchTest(private val instrumentation: Instrumentation) {
    fun runAll(keyword: String): String {
        val events = ConcurrentLinkedQueue<String>()
        val http = DanmakuServices.http.newBuilder().eventListenerFactory {
            object : EventListener() {
                override fun connectionAcquired(call: Call, connection: okhttp3.Connection) { events.add("connected") }
                override fun responseHeadersStart(call: Call) { events.add("headers") }
                override fun callFailed(call: Call, ioe: IOException) {
                    events.add("failed:${ioe.javaClass.simpleName}/${ioe.cause?.javaClass?.simpleName}")
                }
            }
        }.build()
        val api = DanmakuApi(http, DanmakuServices(instrumentation.targetContext).endpoint)
        var successes = 0
        longArrayOf(0, 200, 4_800, 500, 6_000, 15_000).forEachIndexed { index, idleMs ->
            Thread.sleep(idleMs)
            events.clear()
            val started = SystemClock.elapsedRealtime()
            val result = runCatching { runBlocking { api.search(keyword) } }
            if (result.isSuccess) successes++
            val error = result.exceptionOrNull()
            val reason = (error as? DanmakuException)?.reason
            val row = "Live search index=$index idleMs=$idleMs elapsedMs=${SystemClock.elapsedRealtime() - started}" +
                " results=${result.getOrNull()?.size} reason=$reason cause=${error?.cause?.message} events=${events.joinToString()}"
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", row + "\n") })
        }
        check(successes == 6) { "Live search failed: successes=$successes/6" }
        return "Live search completed: successes=$successes/6"
    }
}

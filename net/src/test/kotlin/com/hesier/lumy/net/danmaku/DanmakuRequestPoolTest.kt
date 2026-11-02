package com.hesier.lumy.net.danmaku

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class DanmakuRequestPoolTest {
    private val server = MockWebServer().apply { start() }
    private val http = OkHttpClient()
    private val url get() = server.url("/comments").newBuilder().host("127.0.0.1").build().toString()
    @After fun close() { server.shutdown() }

    @Test fun sameClientUrlAndHeadersShareOnlyPendingRequest() = runBlocking {
        server.enqueue(MockResponse().setBody("shared").setBodyDelay(100, TimeUnit.MILLISECONDS))
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            DanmakuTransport(http).get(url, mapOf("X-Token" to "a", "Accept" to "application/json"))
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            DanmakuTransport(http).get(url, mapOf("accept" to "application/json", "x-token" to "a"))
        }
        val bytes = first.await()
        val other = second.await()
        bytes[0] = 0
        assertEquals("shared", other.toString(Charsets.UTF_8))
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setBody("next"))
        assertEquals("next", DanmakuTransport(http).get(url,
            mapOf("X-Token" to "a", "Accept" to "application/json")).toString(Charsets.UTF_8))
        assertEquals(2, server.requestCount)
    }

    @Test fun oneCancellationPreservesOtherCaller() = runBlocking {
        server.enqueue(MockResponse().setBody("complete").setBodyDelay(100, TimeUnit.MILLISECONDS))
        val first = async(start = CoroutineStart.UNDISPATCHED) { DanmakuTransport(http).get(url) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { DanmakuTransport(http).get(url) }
        first.cancelAndJoin()
        assertEquals("complete", withTimeout(2_000) { second.await() }.toString(Charsets.UTF_8))
        assertEquals(1, server.requestCount)
    }

    @Test fun lastCancellationTerminatesBlockedBodyAndNewCallerCanRetry() = runBlocking {
        val bodyStarted = CompletableDeferred<Unit>()
        val tracked = http.newBuilder().addNetworkInterceptor { chain ->
            chain.proceed(chain.request()).also { bodyStarted.complete(Unit) }
        }.build()
        server.enqueue(MockResponse().setBody("blocked").setBodyDelay(3, TimeUnit.SECONDS))
        val first = async(start = CoroutineStart.UNDISPATCHED) { DanmakuTransport(tracked).get(url) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { DanmakuTransport(tracked).get(url) }
        withTimeout(2_000) { bodyStarted.await() }
        withTimeout(2_000) { first.cancelAndJoin(); second.cancelAndJoin() }
        withTimeout(2_000) { while (tracked.dispatcher.runningCallsCount() > 0) delay(10) }
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setBody("retry"))
        assertEquals("retry", DanmakuTransport(tracked).get(url).toString(Charsets.UTF_8))
        assertEquals(2, server.requestCount)
    }

    @Test fun differentCredentialsAndClientInstancesNeverShare() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setBody("ok").setBodyDelay(100, TimeUnit.MILLISECONDS)) }
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            DanmakuTransport(http).get(url, mapOf("X-Emby-Token" to "user-a"))
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            DanmakuTransport(http).get(url, mapOf("X-Emby-Token" to "user-b"))
        }
        val third = async(start = CoroutineStart.UNDISPATCHED) {
            DanmakuTransport(http.newBuilder().build()).get(url, mapOf("X-Emby-Token" to "user-a"))
        }
        first.await(); second.await(); third.await()
        assertEquals(3, server.requestCount)
        val tokens = List(3) { server.takeRequest().getHeader("X-Emby-Token") }
        assertEquals(2, tokens.count { it == "user-a" })
        assertEquals(1, tokens.count { it == "user-b" })
    }

    @Test fun duplicateHeaderCasingUsesEffectiveLastValue() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setBody("ok").setBodyDelay(100, TimeUnit.MILLISECONDS)) }
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            DanmakuTransport(http).get(url, linkedMapOf("X-Token" to "a", "x-token" to "b"))
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            DanmakuTransport(http).get(url, linkedMapOf("x-token" to "b", "X-Token" to "a"))
        }
        first.await(); second.await()
        assertEquals(2, server.requestCount)
        assertEquals(setOf("a", "b"), List(2) { server.takeRequest().getHeader("X-Token") }.toSet())
    }
}

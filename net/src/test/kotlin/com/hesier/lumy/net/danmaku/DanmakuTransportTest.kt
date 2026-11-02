package com.hesier.lumy.net.danmaku

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import okio.Buffer
import okio.GzipSink
import okio.buffer
import java.util.concurrent.TimeUnit

class DanmakuTransportTest {
    private val first = MockWebServer().apply { start() }
    private val second = MockWebServer().apply { start() }
    private val transport = DanmakuTransport(OkHttpClient())
    @After fun close() { first.shutdown(); second.shutdown() }

    @Test fun recoversWhenReusedConnectionClosesWithoutResponse() = runBlocking {
        first.enqueue(MockResponse().setBody("warm"))
        assertEquals("warm", transport.get(first.url("/warm").toString()).toString(Charsets.UTF_8))
        first.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        first.enqueue(MockResponse().setBody("recovered"))
        val result = transport.get(first.url("/search").toString())
        assertEquals("recovered", result.toString(Charsets.UTF_8))
        assertEquals("/warm", first.takeRequest().path)
        assertEquals("/search", first.takeRequest().path)
        assertEquals("/search", first.takeRequest().path)
        assertEquals(3, first.requestCount)
    }

    @Test fun stopsWhenTheReplacementConnectionAlsoFails() = runBlocking {
        first.enqueue(MockResponse().setBody("warm"))
        transport.get(first.url("/warm").toString())
        repeat(2) { first.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
        val error = runCatching { transport.get(first.url("/search").toString()) }.exceptionOrNull() as DanmakuException
        assertEquals(DanmakuFailure.CONNECTION, error.reason)
        assertEquals(3, first.requestCount)
    }

    @Test fun redirectsRemoveEverySensitiveHeaderAcrossOrigins() = runBlocking {
        first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/next")))
        second.enqueue(MockResponse().setBody("ok"))
        val bytes = transport.get(first.url("/token").toString(), mapOf("Authorization" to "Bearer secret",
            "X-Emby-Token" to "secret", "X-AppId" to "id", "X-Signature" to "signed", "Cookie" to "session=secret"))
        assertEquals("ok", bytes.toString(Charsets.UTF_8))
        val request = second.takeRequest()
        listOf("Authorization", "X-Emby-Token", "X-AppId", "X-Signature", "Cookie").forEach { assertNull(request.getHeader(it)) }
    }

    @Test fun sameOriginKeepsLoginHeadersButRedirectCountIsBounded() = runBlocking {
        first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next"))
        first.enqueue(MockResponse().setBody("ok"))
        transport.get(first.url("/").toString(), mapOf("X-Emby-Token" to "login"))
        first.takeRequest()
        assertEquals("login", first.takeRequest().getHeader("X-Emby-Token"))
        repeat(4) { first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/loop")) }
        val failure = runCatching { transport.get(first.url("/").toString()) }.exceptionOrNull() as DanmakuException
        assertEquals(DanmakuFailure.UNAVAILABLE, failure.reason)
    }

    @Test fun statusFailuresAreTypedAndRedacted() = runBlocking {
        mapOf(401 to DanmakuFailure.AUTH, 403 to DanmakuFailure.AUTH, 404 to DanmakuFailure.NOT_FOUND,
            429 to DanmakuFailure.RATE_LIMITED, 500 to DanmakuFailure.UNAVAILABLE).forEach { (code, expected) ->
            first.enqueue(MockResponse().setResponseCode(code).setBody("private"))
            val error = runCatching { transport.get(first.url("/sensitive-token").toString()) }.exceptionOrNull() as DanmakuException
            assertEquals(expected, error.reason)
            assertFalse(error.stackTraceToString().contains("sensitive-token"))
        }
    }

    @Test fun decodedBodyLimitAppliesToGzipAndChunkedBodies() = runBlocking {
        val expanded = ByteArray(MAX_DANMAKU_BYTES + 1) { 65 }
        val zipped = Buffer()
        GzipSink(zipped).buffer().use { it.write(expanded) }
        first.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(zipped))
        assertEquals(DanmakuFailure.TOO_LARGE,
            (runCatching { transport.get(first.url("/").toString()) }.exceptionOrNull() as DanmakuException).reason)
        val explicitGzip = Buffer()
        GzipSink(explicitGzip).buffer().use { it.write(expanded) }
        first.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(explicitGzip))
        assertEquals(DanmakuFailure.TOO_LARGE, (runCatching {
            transport.get(first.url("/").toString(), mapOf("Accept-Encoding" to "gzip"))
        }.exceptionOrNull() as DanmakuException).reason)
        first.enqueue(MockResponse().setChunkedBody(Buffer().write(expanded), 8192))
        assertEquals(DanmakuFailure.TOO_LARGE,
            (runCatching { transport.get(first.url("/").toString()) }.exceptionOrNull() as DanmakuException).reason)
    }

    @Test fun cancellationInterruptsBlockedBodyRead() = runBlocking {
        val bodyStarted = CompletableDeferred<Unit>()
        val http = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            chain.proceed(chain.request()).also { bodyStarted.complete(Unit) }
        }.build()
        first.enqueue(MockResponse().setBody("slow body").setBodyDelay(3, TimeUnit.SECONDS))
        val result = async(Dispatchers.IO) { DanmakuTransport(http).get(first.url("/").toString()) }
        withTimeout(5_000) { bodyStarted.await() }
        withTimeout(2_000) { result.cancelAndJoin() }
        assertTrue(result.isCancelled)
        withContext(Dispatchers.IO) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (http.dispatcher.runningCallsCount() > 0 && System.nanoTime() < end) Thread.sleep(10)
            assertEquals(0, http.dispatcher.runningCallsCount())
        }
    }
}

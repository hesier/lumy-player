package com.hesier.lumy.fnos

import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class FnOsImageInterceptorTest {
    private val origin = MockWebServer()
    private val destination = MockWebServer()
    private val mediaClient = FnOsClient(FnOsClientInfo("Test Player", "1", "test-device", "Test Box"))
    private val current = AtomicReference<MediaServer?>()
    private val http = OkHttpClient.Builder()
        .addNetworkInterceptor(FnOsImageInterceptor { current.get() })
        .build()

    @After
    fun tearDown() {
        origin.shutdown()
        destination.shutdown()
    }

    private fun mediaServer(kind: ServerKind = ServerKind.FNOS, path: String = "/"): MediaServer {
        val session = ServerSession(
            kind, "test-server", "Test Media", origin.url(path).toString().trimEnd('/'),
            "test-token", userId = "test-user", deviceId = "test-device",
        )
        val delegate = FnOsMediaServer(session.copy(kind = ServerKind.FNOS), mediaClient)
        return object : MediaServer by delegate {
            override val session = session
        }
    }

    private fun load(path: String) {
        val request = Request.Builder().url(origin.url(path))
            .header("X-Emby-Authorization", "stale-test-auth")
            .header("X-Emby-Token", "stale-test-token")
            .build()
        http.newCall(request).execute().use { assertEquals(200, it.code) }
    }

    private fun assertSigned(request: RecordedRequest) {
        assertEquals("test-token", request.getHeader("X-Emby-Token"))
        assertTrue(request.getHeader("X-Emby-Authorization")!!.contains("Token=\"test-token\""))
    }

    private fun assertUnsigned(request: RecordedRequest) {
        assertNull(request.getHeader("X-Emby-Authorization"))
        assertNull(request.getHeader("X-Emby-Token"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun `same origin image receives current fnOS credentials`() {
        current.set(mediaServer())
        origin.enqueue(MockResponse().setBody("image"))
        load("/Items/test-item/Images/Primary")
        assertSigned(origin.takeRequest())
    }

    @Test
    fun `same origin nonimage never receives credentials`() {
        current.set(mediaServer())
        origin.enqueue(MockResponse().setBody("{}"))
        load("/Items/test-item")
        assertUnsigned(origin.takeRequest())
    }

    @Test
    fun `redirect to a different origin strips fnOS credentials`() {
        current.set(mediaServer())
        destination.enqueue(MockResponse().setBody("image"))
        origin.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", destination.url("/Items/test-item/Images/Primary")),
        )
        load("/Items/test-item/Images/Primary")
        assertSigned(origin.takeRequest())
        assertUnsigned(destination.takeRequest())
    }

    @Test
    fun `redirect strips credentials after current server is removed`() {
        redirectWhileSwitching(null)
    }

    @Test
    fun `redirect strips credentials after switching to Jellyfin`() {
        redirectWhileSwitching(ServerKind.JELLYFIN)
    }

    private fun redirectWhileSwitching(replacement: ServerKind?) {
        current.set(mediaServer())
        val next = replacement?.let { mediaServer(it) }
        destination.enqueue(MockResponse().setBody("image"))
        origin.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                current.set(next)
                return MockResponse().setResponseCode(302)
                    .setHeader("Location", destination.url("/Items/test-item/Images/Primary"))
            }
        }
        load("/Items/test-item/Images/Primary")
        assertSigned(origin.takeRequest())
        assertUnsigned(destination.takeRequest())
    }

    @Test
    fun `reverse proxy path restricts credentials to images within that base`() {
        current.set(mediaServer(path = "/media/"))
        repeat(3) { origin.enqueue(MockResponse().setBody("image")) }
        load("/media/Items/test-item/Images/Primary")
        load("/Items/test-item/Images/Primary")
        load("/media-other/Items/test-item/Images/Primary")
        assertSigned(origin.takeRequest())
        assertUnsigned(origin.takeRequest())
        assertUnsigned(origin.takeRequest())
    }
}

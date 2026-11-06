package com.hesier.lumy.jellyfin

import com.hesier.lumy.core.ServerKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class QuickConnectTest {
    private val web = MockWebServer()
    private val client = JellyfinClient(JellyfinClientInfo("Lumy", "1", "stable", "TV"))
    private val connector = JellyfinConnector(client)
    private fun server(kind: ServerKind = ServerKind.JELLYFIN) = JellyfinConnector.Server(
        web.url("/jellyfin").toString(), kind, PublicSystemInfo("server", "My Jellyfin"),
    )
    private fun respond(body: String) = web.enqueue(MockResponse().setBody(body))
    private fun pairing() = respond("""{"Code":"123456","Secret":"private-secret","Authenticated":false}""")

    @After fun close() = web.shutdown()

    @Test fun `pairing exchanges secret and keeps device identity`() = runBlocking {
        respond("true")
        pairing()
        respond("""{"Authenticated":false}""")
        respond("""{"Authenticated":true}""")
        respond("""{"AccessToken":"token","User":{"Id":"user","Name":"Alice"}}""")
        val quick = connector.quickConnect(server())
        val pair = quick.initiate()
        assertEquals("123456", pair.code)
        val result = quick.awaitLogin(pair)
        assertEquals("Alice", result.username)
        assertEquals("token", result.session.token)
        assertEquals("server", result.session.serverId)
        assertEquals("user", result.session.userId)
        val requests = List(5) { web.takeRequest() }
        assertEquals("/jellyfin/QuickConnect/Enabled", requests[0].path)
        assertEquals("POST", requests[1].method)
        assertEquals("/jellyfin/QuickConnect/Connect?secret=private-secret", requests[2].path)
        assertEquals("POST", requests[4].method)
        assertEquals("/jellyfin/Users/AuthenticateWithQuickConnect", requests[4].path)
        assertEquals("""{"Secret":"private-secret"}""", requests[4].body.readUtf8())
        requests.drop(1).forEach {
            assertTrue(it.getHeader("Authorization")!!.contains("DeviceId=\"${result.session.deviceId}\""))
        }
    }

    @Test fun `legacy initiate retries GET only on 404 or 405`() = runBlocking {
        for (status in listOf(404, 405)) {
            respond("true")
            web.enqueue(MockResponse().setResponseCode(status))
            pairing()
            connector.quickConnect(server()).initiate()
            web.takeRequest()
            assertEquals("POST", web.takeRequest().method)
            assertEquals("GET", web.takeRequest().method)
        }
    }

    @Test fun `disabled server never initiates pairing`() = runBlocking {
        respond("false")
        try {
            connector.quickConnect(server()).initiate()
            fail("Expected disabled")
        } catch (error: QuickConnectException) {
            assertEquals(QuickConnectException.Reason.DISABLED, error.reason)
        }
        assertEquals(1, web.requestCount)
    }

    @Test fun `expired secret stops polling`() = runBlocking {
        respond("true")
        pairing()
        web.enqueue(MockResponse().setResponseCode(404))
        val quick = connector.quickConnect(server())
        val pair = quick.initiate()
        try {
            quick.awaitLogin(pair)
            fail("Expected expired")
        } catch (error: QuickConnectException) {
            assertEquals(QuickConnectException.Reason.EXPIRED, error.reason)
        }
        assertEquals(3, web.requestCount)
    }

    @Test fun `cancelling pairing does not exchange credentials`() = runBlocking {
        respond("true")
        pairing()
        respond("""{"Authenticated":false}""")
        val quick = connector.quickConnect(server())
        val pair = quick.initiate()
        val task = async { quick.awaitLogin(pair) }
        yield()
        task.cancel()
        try { task.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertTrue(web.requestCount <= 3)
    }

    @Test fun `initiate failure never retries unrelated HTTP errors`() = runBlocking {
        respond("true")
        web.enqueue(MockResponse().setResponseCode(500))
        try {
            connector.quickConnect(server()).initiate()
            fail("Expected HTTP failure")
        } catch (error: JellyfinHttpException) {
            assertEquals(500, error.code)
        }
        assertEquals(2, web.requestCount)
    }

    @Test fun `disabled during polling stops the attempt`() = runBlocking {
        respond("true")
        pairing()
        web.enqueue(MockResponse().setResponseCode(401))
        val quick = connector.quickConnect(server())
        val pair = quick.initiate()
        try {
            quick.awaitLogin(pair)
            fail("Expected disabled")
        } catch (error: QuickConnectException) {
            assertEquals(QuickConnectException.Reason.DISABLED, error.reason)
        }
        assertEquals(3, web.requestCount)
    }

    @Test fun `other server kinds cannot create Quick Connect`() {
        for (kind in listOf(ServerKind.EMBY, ServerKind.FNOS)) {
            try { connector.quickConnect(server(kind)); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, web.requestCount)
    }
}

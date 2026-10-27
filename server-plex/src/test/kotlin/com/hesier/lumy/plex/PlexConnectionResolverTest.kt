package com.hesier.lumy.plex

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class PlexConnectionResolverTest {
    private val client = PlexClient(PlexClientInfo("Test", "1", "id", "12", "Emu"))
    private val resolver = PlexConnectionResolver(client)
    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() = servers.forEach { it.shutdown() }

    private fun server(machineId: String, delayMs: Long = 0): String {
        val s = MockWebServer()
        repeat(3) {
            s.enqueue(
                MockResponse()
                    .setBody("""{"MediaContainer":{"machineIdentifier":"$machineId"}}""")
                    .setBodyDelay(delayMs, TimeUnit.MILLISECONDS),
            )
        }
        s.start()
        servers += s
        return s.url("/").toString()
    }

    private fun resource(vararg connections: PlexConnection) =
        PlexResource(name = "NAS", clientIdentifier = "m1", provides = "server", accessToken = "t", connections = connections.toList())

    @Test
    fun `slightly slower local connection beats faster remote one`() = runBlocking {
        val local = server("m1", delayMs = 300)
        val remote = server("m1")
        val session = resolver.resolve(
            resource(
                PlexConnection(uri = remote, local = false),
                PlexConnection(uri = local, local = true),
            ),
        )
        assertEquals(local.trimEnd('/'), session!!.baseUrl)
    }

    @Test
    fun `wrong machine identifier is rejected and relay is the fallback`() = runBlocking {
        val impostor = server("other")
        val relay = server("m1")
        val session = resolver.resolve(
            resource(
                PlexConnection(uri = impostor, local = true),
                PlexConnection(uri = relay, relay = true),
            ),
        )
        assertEquals(relay.trimEnd('/'), session!!.baseUrl)
    }

    @Test
    fun `local plex direct name that does not resolve falls back to the LAN IP over http`() = runBlocking {
        val lan = server("m1", delayMs = 200)
        val public = server("m1")
        val lanPort = okhttp3.HttpUrl.Companion.run { lan.toHttpUrl() }.port
        val session = resolver.resolve(
            resource(
                PlexConnection(protocol = "https", uri = public, local = false),
                // 路由器不解析 plex.direct：这个域名本身连不上
                PlexConnection(protocol = "https", uri = "https://127-0-0-1.abc.invalid.plex.direct:$lanPort", address = "127.0.0.1", port = lanPort, local = true),
            ),
        )
        assertEquals("http://127.0.0.1:$lanPort", session!!.baseUrl)
    }

    @Test
    fun `no reachable connection gives null`() = runBlocking {
        assertNull(resolver.resolve(resource(PlexConnection(uri = "http://127.0.0.1:1/", local = true))))
    }
}

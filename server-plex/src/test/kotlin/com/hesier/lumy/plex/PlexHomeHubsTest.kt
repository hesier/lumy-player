package com.hesier.lumy.plex

import com.hesier.lumy.core.HubLabel
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexHomeHubsTest {
    private val web = MockWebServer()
    private val client = PlexClient(PlexClientInfo("Test", "1", "id", "12", "Emu"))
    private val recentlyAdded = """{"hubIdentifier":"movie.recentlyadded","title":"Recently Added",
        "Metadata":[{"ratingKey":"1","type":"movie","title":"Movie"}]}"""

    @After
    fun tearDown() = web.shutdown()

    private fun server() = PlexMediaServer(
        ServerSession(ServerKind.PLEX, "srv", "NAS", web.url("/").toString().trimEnd('/'), "token"), client,
    )

    private fun promoted(vararg hubs: String) = MockResponse().setBody(
        """{"MediaContainer":{"Hub":[${hubs.joinToString(",")}]}}""",
    )

    @Test
    fun `failed continue request without a fallback hub fails home loading`() = runBlocking {
        for (status in listOf(500, 404)) {
            web.enqueue(MockResponse().setResponseCode(status))
            web.enqueue(promoted(recentlyAdded))
            val error = runCatching { server().homeHubs() }.exceptionOrNull()
            assertTrue("HTTP $status must not become an authoritative empty resume list", error is PlexHttpException)
            assertEquals(status, (error as PlexHttpException).code)
        }
    }

    @Test
    fun `missing continue endpoint falls back to a promoted continue hub`() = runBlocking {
        web.enqueue(MockResponse().setResponseCode(404))
        web.enqueue(promoted(recentlyAdded, """{"hubIdentifier":"home.ondeck","title":"Continue Watching",
            "Metadata":[{"ratingKey":"2","type":"episode","title":"Episode","duration":600000,"viewOffset":120000}]}"""))
        val hubs = server().homeHubs()
        val resume = hubs.single { it.label == HubLabel.ContinueWatching }
        assertEquals("2", resume.items.single().id)
        assertEquals(2, hubs.size)
    }

    @Test
    fun `successful empty continue response keeps the other home hubs`() = runBlocking {
        web.enqueue(promoted())
        web.enqueue(promoted(recentlyAdded))
        val hubs = server().homeHubs()
        assertEquals("1", hubs.single().items.single().id)
        assertTrue(hubs.none { it.label == HubLabel.ContinueWatching })
    }

    @Test
    fun `empty promoted continue hub is a valid fallback response`() = runBlocking {
        web.enqueue(MockResponse().setResponseCode(404))
        web.enqueue(promoted("""{"hubIdentifier":"home.continue","title":"Continue Watching","Metadata":[]}"""))
        assertTrue(server().homeHubs().isEmpty())
    }
}

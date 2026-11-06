package com.hesier.lumy.jellyfin

import com.hesier.lumy.core.HubLabel
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeHubsTest {
    private val web = MockWebServer()
    private val client = JellyfinClient(JellyfinClientInfo("Lumy", "1", "dev1", "Box"))

    @After
    fun tearDown() = web.shutdown()

    private fun server(kind: ServerKind) = JellyfinMediaServer(
        ServerSession(kind, "srv", "NAS", web.url("/").toString().trimEnd('/'), "tok", userId = "u1"),
        client,
    )

    private fun respondToHomeRequests(resumeStatus: Int) {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                return when {
                    url.queryParameter("Filters") == "IsResumable" ->
                        MockResponse().setResponseCode(resumeStatus).setBody("""{"Items":[]}""")
                    url.encodedPath in listOf("/UserViews", "/Users/u1/Views") ->
                        MockResponse().setBody("""{"Items":[{"Id":"lib","Name":"Movies","CollectionType":"movies"}]}""")
                    url.queryParameter("ParentId") == "lib" ->
                        MockResponse().setBody("""{"Items":[{"Id":"movie","Name":"Movie","Type":"Movie"}]}""")
                    url.encodedPath == "/Shows/NextUp" -> MockResponse().setBody("""{"Items":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    @Test
    fun `empty resume response still returns recently added hubs for both servers`() = runBlocking {
        respondToHomeRequests(200)
        for (kind in listOf(ServerKind.JELLYFIN, ServerKind.EMBY)) {
            val hubs = server(kind).homeHubs()
            assertFalse(hubs.any { it.label == HubLabel.ContinueWatching })
            assertEquals(HubLabel.RecentlyAdded("Movies"), hubs.single().label)
            assertEquals("movie", hubs.single().items.single().id)
        }
    }

    @Test
    fun `resume failure propagates despite successful other home requests for both servers`() = runBlocking {
        respondToHomeRequests(500)
        for (kind in listOf(ServerKind.JELLYFIN, ServerKind.EMBY)) {
            val error = runCatching { server(kind).homeHubs() }.exceptionOrNull()
            assertTrue("$kind must fail instead of returning an incomplete home", error is JellyfinHttpException)
            assertEquals(500, (error as JellyfinHttpException).code)
        }
    }
}

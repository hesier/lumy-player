package com.hesier.lumy.plex

import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.SearchScope
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PlexSearchTest {
    private val web = MockWebServer()
    private val media = PlexMediaServer(
        ServerSession(ServerKind.PLEX, "srv", "NAS", web.url("/").toString().trimEnd('/'), "token"),
        PlexClient(PlexClientInfo("Test", "1", "id", "12", "Emu")),
    )

    @After fun tearDown() = web.shutdown()

    private fun result(type: String, count: Int, more: String? = "false"): MockResponse {
        val rows = (0 until count).joinToString(",") { index ->
            """{"ratingKey":"$index","type":"$type","title":"Unrelated title $index","librarySectionID":2}"""
        }
        val marker = more?.let { "\"more\":$it," }.orEmpty()
        return MockResponse().setBody("""{"MediaContainer":{"Hub":[{"hubIdentifier":"$type","type":"$type",$marker"Metadata":[$rows]}]}}""")
    }

    @Test fun `broad query expands each video category beyond preview then pages complete matches`() = runBlocking {
        for ((type, apiType) in listOf(MediaType.MOVIE to "movie", MediaType.SHOW to "show", MediaType.SEASON to "season", MediaType.EPISODE to "episode")) {
            for ((count, more) in listOf(20 to "true", 40 to "1", 75 to "false")) {
                web.enqueue(result(apiType, count, more))
            }
            val page = media.searchItems(" actor name ", SearchScope(type, "2"), 60, 10)
            assertEquals(75, page.total)
            assertEquals(60, page.offset)
            assertEquals((60..69).map(Int::toString), page.items.map { it.id })
            for (limit in listOf("20", "40", "80")) {
                val url = web.takeRequest().requestUrl!!
                assertEquals("/hubs/search", url.encodedPath)
                assertEquals("actor name", url.queryParameter("query"))
                assertEquals(limit, url.queryParameter("limit"))
                assertNull(url.queryParameter("title"))
            }
        }
    }

    @Test fun `same title libraries keep distinct scopes and original broad search previews`() = runBlocking {
        val body = """{"MediaContainer":{"Hub":[{"hubIdentifier":"movie","type":"movie","more":false,"Metadata":[{"ratingKey":"a","title":"Different title","type":"movie","librarySectionID":1,"librarySectionTitle":"Films"},{"ratingKey":"b","title":"Another title","type":"movie","librarySectionID":2,"librarySectionTitle":"Films"}]}]}}"""
        web.enqueue(MockResponse().setBody(body))
        val hubs = media.search("actor name")
        assertEquals(listOf("1", "2"), hubs.map { it.searchScope!!.libraryId })
        assertEquals(2, hubs.map { it.id }.distinct().size)
        assertEquals(listOf("a", "b"), hubs.map { it.items.single().id })
        assertEquals(1, web.requestCount)
        for (hub in hubs) {
            web.enqueue(MockResponse().setBody(body))
            val page = media.searchItems("actor name", hub.searchScope!!, 0, 20)
            assertEquals(hub.items, page.items)
            assertEquals(1, page.total)
        }
    }

    @Test fun `explicit complete marker preserves empty final page and blank search makes no request`() = runBlocking {
        web.enqueue(result("movie", 75, "0"))
        val page = media.searchItems("actor name", SearchScope(MediaType.MOVIE), 80, 20)
        assertTrue(page.items.isEmpty())
        assertEquals(75, page.total)
        assertEquals(80, page.offset)
        assertEquals(0, media.searchItems(" ", SearchScope(MediaType.MOVIE), 0, 20).total)
        assertTrue(runCatching { media.searchItems("x", SearchScope(MediaType.MUSIC), 0, 20) }.isFailure)
        assertTrue(runCatching { media.searchItems("x", SearchScope(MediaType.MOVIE), -1, 20) }.isFailure)
        assertTrue(runCatching { media.searchItems("x", SearchScope(MediaType.MOVIE), 0, 0) }.isFailure)
        assertEquals(1, web.requestCount)
    }

    @Test fun `server capped or unmarked results fail without fabricated total`() = runBlocking {
        for (marker in listOf("true", null)) {
            repeat(2) { web.enqueue(result("movie", 5, marker)) }
            val outcome = runCatching { media.searchItems("actor name", SearchScope(MediaType.MOVIE), 0, 20) }
            assertTrue(outcome.exceptionOrNull() is IllegalStateException)
        }
        assertEquals(4, web.requestCount)
    }

    @Test fun `changing rank and unbounded expansion fail visibly`() = runBlocking {
        web.enqueue(result("movie", 2, "true"))
        web.enqueue(result("movie", 1, "false"))
        assertTrue(runCatching {
            media.searchItems("actor", SearchScope(MediaType.MOVIE), 0, 20)
        }.isFailure)
        for (count in 1..10) web.enqueue(result("movie", count, "true"))
        assertTrue(runCatching {
            media.searchItems("actor", SearchScope(MediaType.MOVIE), 0, 20)
        }.isFailure)
        assertEquals(12, web.requestCount)
    }

    @Test fun `empty search response means no results`() = runBlocking {
        web.enqueue(MockResponse().setBody("""{"MediaContainer":{"Hub":[]}}"""))
        val page = media.searchItems("missing", SearchScope(MediaType.MOVIE), 0, 20)
        assertEquals(0, page.total)
        assertTrue(page.items.isEmpty())
    }
}

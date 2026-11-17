package com.hesier.lumy.jellyfin

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

class SearchTest {
    private val web = MockWebServer()
    private fun media(kind: ServerKind) = JellyfinMediaServer(
        ServerSession(kind, "srv", "NAS", web.url("/").toString().trimEnd('/'), "token", userId = "u1"),
        JellyfinClient(JellyfinClientInfo("Test", "1", "id", "Box")),
    )
    @After fun tearDown() = web.shutdown()

    @Test fun `Jellyfin and Emby page each category beyond preview using compatible Items`() = runBlocking {
        for (kind in listOf(ServerKind.JELLYFIN, ServerKind.EMBY)) {
            for ((type, apiType) in listOf(MediaType.MOVIE to "Movie", MediaType.SHOW to "Series", MediaType.EPISODE to "Episode")) {
                web.enqueue(MockResponse().setBody("""{"Items":[{"Id":"61","Type":"$apiType","Name":"needle"}],"TotalRecordCount":100}"""))
                val page = media(kind).searchItems(" needle ", SearchScope(type, "library"), 60, 10)
                assertEquals(100, page.total)
                assertEquals(60, page.offset)
                assertEquals(type, page.items.single().type)
                val url = web.takeRequest().requestUrl!!
                assertEquals("/Items", url.encodedPath)
                assertEquals("u1", url.queryParameter("UserId"))
                assertEquals("needle", url.queryParameter("SearchTerm"))
                assertEquals(apiType, url.queryParameter("IncludeItemTypes"))
                assertEquals("library", url.queryParameter("ParentId"))
                assertEquals("true", url.queryParameter("Recursive"))
                assertEquals("60", url.queryParameter("StartIndex"))
                assertEquals("10", url.queryParameter("Limit"))
                assertEquals("SortName,DateCreated", url.queryParameter("SortBy"))
            }
        }
    }

    @Test fun `preview loads each category and exposes matching scope`() = runBlocking {
        for (type in listOf("Movie", "Series", "Episode")) {
            web.enqueue(MockResponse().setBody("""{"Items":[{"Id":"$type","Type":"$type"}],"TotalRecordCount":80}"""))
        }
        val hubs = media(ServerKind.EMBY).search("needle")
        assertEquals(listOf(MediaType.MOVIE, MediaType.SHOW, MediaType.EPISODE), hubs.map { it.searchScope!!.type })
        repeat(3) {
            val url = web.takeRequest().requestUrl!!
            assertEquals("20", url.queryParameter("Limit"))
            assertEquals("0", url.queryParameter("StartIndex"))
        }
    }

    @Test fun `empty page and invalid input do not fabricate results`() = runBlocking {
        val media = media(ServerKind.JELLYFIN)
        web.enqueue(MockResponse().setBody("""{"Items":[],"TotalRecordCount":80}"""))
        val page = media.searchItems("needle", SearchScope(MediaType.MOVIE), 80, 20)
        assertTrue(page.items.isEmpty())
        assertEquals(80, page.total)
        assertEquals(0, media.searchItems(" ", SearchScope(MediaType.MOVIE), 0, 20).total)
        assertTrue(runCatching { media.searchItems("x", SearchScope(MediaType.SEASON), 0, 20) }.isFailure)
        assertTrue(runCatching { media.searchItems("x", SearchScope(MediaType.MOVIE), -1, 20) }.isFailure)
        assertTrue(runCatching { media.searchItems("x", SearchScope(MediaType.MOVIE), 0, 0) }.isFailure)
        assertEquals(1, web.requestCount)
    }
}

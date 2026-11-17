package com.hesier.lumy.fnos

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

class FnOsSearchTest {
    private val native = FnOsTestDispatcher()
    private val web = MockWebServer().apply { dispatcher = native }
    private val media = FnOsMediaServer(
        ServerSession(ServerKind.FNOS, "srv", "NAS", web.url("/").toString().trimEnd('/'), "token"),
        FnOsClient(FnOsClientInfo("Test", "1", "id", "Box")),
    )
    @After fun tearDown() = web.shutdown()

    @Test fun `full native matches map deduplicate and page after preview cutoff per library`() = runBlocking {
        val ids = (0..74).map { "file-${it.toString().padStart(3, '0')}" }
        val catalog = ids.joinToString(",") { """{"Id":"$it","Name":"Needle","Type":"Movie","MediaSources":[{"Id":"canonical-$it"}]}""" }
        web.enqueue(MockResponse().setBody("""{"Items":[$catalog],"TotalRecordCount":75}"""))
        native.search = "[" + ids.reversed().joinToString(",") { """{"guid":"canonical-$it","ancestor_guid":"films"}""" } +
            """,{"guid":"canonical-file-000","ancestor_guid":"films"},{"guid":"missing","ancestor_guid":"films"},{"guid":"other-show","ancestor_guid":"shows"}]"""
        val scope = SearchScope(MediaType.MOVIE, "films")
        val page = media.searchItems(" needle ", scope, 60, 10)
        assertEquals(75, page.total)
        assertEquals(60, page.offset)
        assertEquals(ids.drop(60).take(10), page.items.map { it.id })
        web.enqueue(MockResponse().setBody("""{"Items":[{"Id":"films","Name":"Films"}]}"""))
        web.enqueue(MockResponse().setBody("""{"Items":[{"Id":"file-000","Type":"Movie"}]}"""))
        val preview = media.search("needle").single()
        assertEquals(scope, preview.searchScope)
        assertEquals(ids.take(60), preview.items.map { it.id })
        assertEquals(ids.take(20), media.searchItems("needle", scope, 0, 20).items.map { it.id })
        assertTrue(media.searchItems("needle", scope, 75, 10).items.isEmpty())
        assertEquals(75, media.searchItems("needle", scope, 80, 10).total)
        web.enqueue(MockResponse().setBody("""{"Items":[{"Id":"other-show","Name":"Needle","Type":"Series"}],"TotalRecordCount":1}"""))
        val shows = media.searchItems("needle", SearchScope(MediaType.SHOW, "shows"), 0, 20)
        assertEquals(listOf("other-show"), shows.items.map { it.id })
        assertEquals(1, shows.total)
        assertTrue(native.requests.filter { it.requestUrl!!.encodedPath.endsWith("/search/list") }
            .all { it.requestUrl!!.queryParameter("q") == "needle" })
    }

    @Test fun `blank and invalid searches do not make network requests`() = runBlocking {
        val scope = SearchScope(MediaType.MOVIE, "films")
        assertEquals(0, media.searchItems(" ", scope, 0, 20).total)
        assertTrue(runCatching { media.searchItems("x", scope, -1, 20) }.isFailure)
        assertTrue(runCatching { media.searchItems("x", scope, 0, 0) }.isFailure)
        assertTrue(runCatching { media.searchItems("x", SearchScope(MediaType.EPISODE, "films"), 0, 20) }.isFailure)
        assertTrue(runCatching { media.searchItems("x", SearchScope(MediaType.MOVIE), 0, 20) }.isFailure)
        assertEquals(0, web.requestCount)
    }
}

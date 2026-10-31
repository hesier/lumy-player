package com.hesier.lumy.fnos

import com.hesier.lumy.core.LibraryQuery
import com.hesier.lumy.core.ResolutionFilter
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import com.hesier.lumy.core.SortOrder
import com.hesier.lumy.core.WatchFilter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FnOsQueriesTest {
    private val fixture = FnOsTestDispatcher()
    private val web = MockWebServer().apply { dispatcher = fixture }
    private val client = FnOsClient(FnOsClientInfo("Test", "1", "device", "Box"))
    private val queries = FnOsQueries(FnOsNativeClient(
        ServerSession(ServerKind.FNOS, "server", "Test", web.url("/").toString().trimEnd('/'), "token"), client,
    ))

    @After
    fun close() = web.shutdown()

    @Test
    fun `native page sends library filter sort and server pagination`() = runBlocking {
        fixture.items = """{"list":[{"guid":"canonical","watched":1}],"total":41}"""
        val query = LibraryQuery(watch = WatchFilter.WATCHED, genreId = "2",
            resolution = ResolutionFilter.UHD_4K, sort = SortOrder.RATING)
        val result = queries.page("movies", 20, 10, query)
        assertEquals(41, result.total)
        assertEquals(20, result.offset)
        val params = parameters().single()
        assertEquals("movies", params["ancestor_guid"]!!.jsonPrimitive.content)
        assertEquals("3", params["page"]!!.jsonPrimitive.content)
        assertEquals("10", params["page_size"]!!.jsonPrimitive.content)
        assertEquals("vote_average", params["sort_column"]!!.jsonPrimitive.content)
        assertEquals("DESC", params["sort_type"]!!.jsonPrimitive.content)
        val tags = params["tags"]!!.jsonObject
        assertEquals("1", tags["watched"]!!.jsonPrimitive.content)
        assertEquals("2", tags["genres"]!!.jsonPrimitive.content)
        assertEquals("4k", tags["resolution"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unaligned offsets fetch at most the two intersecting native pages`() = runBlocking {
        fixture.items = """{"list":[{"guid":"a"},{"guid":"b"},{"guid":"c"}],"total":9}"""
        val result = queries.page("movies", 2, 3, LibraryQuery())
        assertEquals(listOf("c", "a", "b"), result.items.map { it["guid"]!!.jsonPrimitive.content })
        assertEquals(listOf("1", "2"), parameters().map { it["page"]!!.jsonPrimitive.content })
        val tags = parameters().first()["tags"]!!.jsonObject
        assertFalse(tags.containsKey("watched"))
        assertFalse(tags.containsKey("genres"))
        assertFalse(tags.containsKey("resolution"))
    }

    @Test
    fun `genre options use native library scope and native identifiers`() = runBlocking {
        fixture.tags = """{"genres":[2]}"""
        fixture.genres = """[{"id":1,"value":"Action"},{"id":2,"value":"Adventure"}]"""
        assertEquals(listOf("2"), queries.genres("library / one").map { it.id })
        val request = fixture.requests.single { it.requestUrl!!.encodedPath.endsWith("/tag/list") }
        assertEquals("library / one", request.requestUrl!!.queryParameter("ancestor_guid"))
    }

    @Test
    fun `canonical movie match retains playable file ID images and native watched state`() {
        val row = client.json.parseToJsonElement("""{"guid":"canonical","watched":1}""").jsonObject
        val file = BaseItem("file", type = "Movie", imageTags = mapOf("Primary" to "image"),
            mediaSources = listOf(MediaSourceDto("canonical")))
        val result = row.fnOsMatch(listOf(file))!!
        assertEquals("file", result.id)
        assertEquals(file.imageTags, result.imageTags)
        assertTrue(result.userData!!.played)
    }

    @Test
    fun `last viewed uses compatibility dates instead of ignored native sort columns`() = runBlocking {
        fixture.items = """{"list":[{"guid":"a"},{"guid":"b"}],"total":2}"""
        web.enqueue(MockResponse().setBody("""{"Items":[
            {"Id":"a","Type":"Movie","UserData":{"LastPlayedDate":"2026-10-01"}},
            {"Id":"b","Type":"Movie","UserData":{"LastPlayedDate":"2026-10-30"}}
        ],"TotalRecordCount":2}"""))
        val server = FnOsMediaServer(ServerSession(ServerKind.FNOS, "server", "Test",
            web.url("/").toString().trimEnd('/'), "token", userId = "user"), client)
        val result = server.libraryItems("movies", 0, 1, LibraryQuery(sort = SortOrder.LAST_VIEWED))
        assertEquals(listOf("b"), result.items.map { it.id })
        assertEquals(2, result.total)
        assertEquals("sort_title", parameters().single()["sort_column"]!!.jsonPrimitive.content)
    }

    private fun parameters() = fixture.requests.filter { it.requestUrl!!.encodedPath.endsWith("/item/list") }
        .map { client.json.parseToJsonElement(it.body.clone().readUtf8()).jsonObject }
}

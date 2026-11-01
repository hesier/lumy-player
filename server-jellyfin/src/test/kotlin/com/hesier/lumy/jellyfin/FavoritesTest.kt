package com.hesier.lumy.jellyfin

import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesTest {
    private val web = MockWebServer()
    private val client = JellyfinClient(JellyfinClientInfo("Lumy", "1", "dev1", "Box"))

    @After
    fun tearDown() = web.shutdown()

    private fun server(kind: ServerKind) = JellyfinMediaServer(
        ServerSession(kind, "srv", "NAS", web.url("/").toString().trimEnd('/'), "tok", userId = "u1"),
        client,
    )

    @Test
    fun `both servers filter and page each favorite category`() = runBlocking {
        for (kind in listOf(ServerKind.JELLYFIN, ServerKind.EMBY)) {
            val server = server(kind)
            assertTrue(server.supportsFavorites)
            for ((type, apiType) in listOf(
                MediaType.MOVIE to "Movie", MediaType.SHOW to "Series", MediaType.EPISODE to "Episode",
            )) {
                web.enqueue(MockResponse().setBody(
                    """{"Items":[{"Id":"i1","Type":"$apiType","UserData":{"IsFavorite":true}}],"TotalRecordCount":42}""",
                ))
                val page = server.favorites(type, offset = 20, limit = 10)
                assertEquals(20, page.offset)
                assertEquals(42, page.total)
                assertEquals(type, page.items.single().type)
                assertTrue(page.items.single().favorite)
                val url = web.takeRequest().requestUrl!!
                assertEquals("/Items", url.encodedPath)
                assertEquals("u1", url.queryParameter("UserId"))
                assertEquals("IsFavorite", url.queryParameter("Filters"))
                assertEquals(apiType, url.queryParameter("IncludeItemTypes"))
                assertEquals("true", url.queryParameter("Recursive"))
                assertEquals("20", url.queryParameter("StartIndex"))
                assertEquals("10", url.queryParameter("Limit"))
                assertEquals("SortName", url.queryParameter("SortBy"))
            }
        }
    }

    @Test
    fun `empty final page preserves offset and server total`() = runBlocking {
        web.enqueue(MockResponse().setBody("""{"Items":[],"TotalRecordCount":20}"""))
        val page = server(ServerKind.JELLYFIN).favorites(MediaType.MOVIE, 20, 10)
        assertTrue(page.items.isEmpty())
        assertEquals(20, page.offset)
        assertEquals(20, page.total)
    }

    @Test
    fun `jellyfin writes favorites with authenticated modern endpoint`() = runBlocking {
        val server = server(ServerKind.JELLYFIN)
        for (favorite in listOf(true, false)) {
            web.enqueue(MockResponse().setResponseCode(204))
            server.setFavorite("m1", favorite)
            val request = web.takeRequest()
            assertEquals(if (favorite) "POST" else "DELETE", request.method)
            assertEquals("/UserFavoriteItems/m1?userId=u1", request.path)
            assertTrue(request.getHeader("Authorization")!!.contains("Token=\"tok\""))
        }
    }

    @Test
    fun `jellyfin falls back only for missing favorite route`() = runBlocking {
        val server = server(ServerKind.JELLYFIN)
        for (favorite in listOf(true, false)) {
            web.enqueue(MockResponse().setResponseCode(404))
            web.enqueue(MockResponse().setResponseCode(204))
            server.setFavorite("m1", favorite)
            assertEquals("/UserFavoriteItems/m1?userId=u1", web.takeRequest().path)
            val fallback = web.takeRequest()
            assertEquals("/Users/u1/FavoriteItems/m1", fallback.path)
            assertEquals(if (favorite) "POST" else "DELETE", fallback.method)
        }
    }

    @Test
    fun `emby writes favorites directly using user route and emby token`() = runBlocking {
        val server = server(ServerKind.EMBY)
        for (favorite in listOf(true, false)) {
            web.enqueue(MockResponse().setResponseCode(204))
            server.setFavorite("m1", favorite)
            val request = web.takeRequest()
            assertEquals(if (favorite) "POST" else "DELETE", request.method)
            assertEquals("/Users/u1/FavoriteItems/m1", request.path)
            assertEquals("tok", request.getHeader("X-Emby-Token"))
        }
    }

    @Test
    fun `write failures propagate without retry or false success`() = runBlocking {
        for (kind in listOf(ServerKind.JELLYFIN, ServerKind.EMBY)) {
            for (favorite in listOf(true, false)) {
                web.enqueue(MockResponse().setResponseCode(403))
                val error = runCatching { server(kind).setFavorite("m1", favorite) }.exceptionOrNull()
                assertTrue(error is JellyfinHttpException)
                assertEquals(403, (error as JellyfinHttpException).code)
                web.takeRequest()
            }
        }
        assertEquals(4, web.requestCount)
    }

    @Test
    fun `favorite list failure is not returned as an empty page`() = runBlocking {
        web.enqueue(MockResponse().setResponseCode(500))
        val error = runCatching { server(ServerKind.EMBY).favorites(MediaType.SHOW, 0, 20) }.exceptionOrNull()
        assertTrue(error is JellyfinHttpException)
        assertEquals(500, (error as JellyfinHttpException).code)
    }

    @Test
    fun `user data favorite maps consistently to browse and detail`() {
        for (type in listOf("Movie", "Series", "Episode")) {
            for (favorite in listOf(true, false)) {
                val dto = client.json.decodeFromString<BaseItem>(
                    """{"Id":"i1","Type":"$type","UserData":{"IsFavorite":$favorite}}""",
                )
                assertEquals(favorite, dto.toItem().favorite)
                assertEquals(favorite, dto.toDetail(emptyList(), null).item.favorite)
            }
        }
        assertFalse(client.json.decodeFromString<BaseItem>("""{"Id":"i1"}""").toItem().favorite)
        assertFalse(client.json.decodeFromString<BaseItem>("""{"Id":"i1","UserData":{}}""").toItem().favorite)
    }
}

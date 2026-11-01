package com.hesier.lumy.fnos

import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FnOsFavoritesTest {
    private val fixture = FnOsTestDispatcher()
    private val web = MockWebServer().apply { dispatcher = fixture }
    private val client = FnOsClient(FnOsClientInfo("Test", "1", "device", "Box"))
    private val session = ServerSession(ServerKind.FNOS, "server", "Test",
        web.url("/").toString().trimEnd('/'), "token", userId = "user")
    private val server = FnOsMediaServer(session, client)
    private val favorites = FnOsFavorites(FnOsNativeClient(session, client))

    @After
    fun close() = web.shutdown()

    @Test
    fun `all favorite categories use native types and empty null lists retain total`() = runBlocking {
        fixture.favorites = """{"list":null,"total":0}"""
        for (type in listOf(MediaType.MOVIE, MediaType.SHOW, MediaType.EPISODE)) {
            assertEquals(emptyList<Any>(), favorites.page(type, 0, 20).items)
        }
        assertEquals(listOf("Movie", "TV", "Episode"), parameters().map {
            it["tags"]!!.jsonObject["type"].toString().removePrefix("[\"").removeSuffix("\"]")
        })
    }

    @Test
    fun `unaligned favorite offsets fetch both intersecting pages`() = runBlocking {
        fixture.favorites = """{"list":[{"guid":"a"},{"guid":"b"}],"total":5}"""
        val page = favorites.page(MediaType.MOVIE, 1, 2)
        assertEquals(listOf("b", "a"), page.items.map { it["guid"]!!.jsonPrimitive.content })
        assertEquals(5, page.total)
        assertEquals(listOf("1", "2"), parameters().map { it["page"]!!.jsonPrimitive.content })
    }

    @Test
    fun `episode favorite resolves media file and preserves navigation and native state`() = runBlocking {
        fixture.favorites = """{"list":[{"guid":"canonical","video_guid":"stream","type":"Episode","is_favorite":1,"watched":1}],"total":1}"""
        fixture.info = """{"media_guid":"file"}"""
        web.enqueue(MockResponse().setBody(EPISODE))
        web.enqueue(MockResponse().setBody("""{"Items":[{"Id":"season","Type":"Season","SeriesId":"series"}]}"""))
        val result = server.favorites(MediaType.EPISODE, 0, 20).items.single()
        assertEquals("file", result.id)
        assertEquals("series", result.grandparentId)
        assertEquals("season", result.parentId)
        assertEquals(2, result.episodeNumber)
        assertTrue(result.favorite)
        assertTrue(result.watched)
        assertEquals(2, fixture.compatCount.get())
        web.enqueue(MockResponse().setBody("""{"Items":[$EPISODE]}"""))
        assertEquals(listOf("file"), server.children(result.parentId!!).map { it.id })
        assertEquals("""{"item_guid":"canonical"}""", fixture.requests.single {
            it.requestUrl!!.encodedPath.endsWith("/play/info")
        }.body.readUtf8())
    }

    @Test
    fun `series favorite uses canonical ID without playback lookup`() = runBlocking {
        fixture.favorites = """{"list":[{"guid":"series","type":"TV","is_favorite":1}],"total":1}"""
        web.enqueue(MockResponse().setBody("""{"Id":"series","Type":"Series"}"""))
        assertTrue(server.favorites(MediaType.SHOW, 0, 20).items.single().favorite)
        assertTrue(fixture.requests.none { it.requestUrl!!.encodedPath.endsWith("/play/info") })
    }

    @Test
    fun `favorite PUT and DELETE use canonical ID and detail reads native favorite state`() = runBlocking {
        for (favorite in listOf(true, false)) {
            web.enqueue(MockResponse().setBody(EPISODE))
            server.setFavorite("file", favorite)
        }
        val requests = fixture.requests.filter { it.requestUrl!!.encodedPath.endsWith("/item/favorite") }
        assertEquals(listOf("PUT", "DELETE"), requests.map { it.method })
        assertEquals(listOf("""{"item_guid":"canonical"}""", """{"item_guid":"canonical"}"""),
            requests.map { it.body.readUtf8() })
        fixture.itemState = """{"is_watched":1,"is_favorite":1}"""
        web.enqueue(MockResponse().setBody(EPISODE))
        assertTrue(server.detail("file").item.favorite)
    }

    @Test
    fun `idempotent favorite updates accept native success with false data`() = runBlocking {
        fixture.favoriteResult = "false"
        favorites.set("canonical", true)
    }

    @Test(expected = IOException::class)
    fun `rejected native favorite update fails`() = runBlocking {
        fixture.favoriteCode = 1001
        favorites.set("canonical", true)
    }

    private fun parameters() = fixture.requests.filter { it.requestUrl!!.encodedPath.endsWith("/favorite/list") }
        .map { client.json.parseToJsonElement(it.body.clone().readUtf8()).jsonObject }

    companion object {
        private const val EPISODE = """{"Id":"file","Type":"Episode","SeriesId":"series","SeasonId":"season",
            "IndexNumber":2,"UserData":{"IsFavorite":false},"MediaSources":[{"Id":"canonical"}]}"""
    }
}

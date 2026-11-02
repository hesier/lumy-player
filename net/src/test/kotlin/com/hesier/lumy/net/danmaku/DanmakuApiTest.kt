package com.hesier.lumy.net.danmaku

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import okio.ByteString.Companion.toByteString
import java.security.MessageDigest

class DanmakuApiTest {
    private val server = MockWebServer().apply { start() }
    private fun api(prefix: String = "/") = DanmakuApi(OkHttpClient(),
        DanmakuEndpoint(server.url(prefix).toString(), credentials = DanmakuCredentials("secret-id", "secret-key")))
    @After fun close() { server.shutdown() }

    @Test fun endpointValidatesAndHashesEntireNormalizedPrefix() {
        val first = DanmakuEndpoint("https://EXAMPLE.com:443/token")
        assertEquals(first.identity, DanmakuEndpoint("https://example.com/token/").identity)
        assertNotEquals(first.identity, DanmakuEndpoint("https://example.com/other/").identity)
        assertFalse(first.toString().contains("token"))
        assertFalse(DanmakuCredentials("id", "secret").toString().contains("secret"))
        listOf("file:///tmp/a", "ftp://host/a", "https://u:p@host/", "https://@host/",
            "https://host/?token=x", "https://host/#x").forEach {
            assertTrue(runCatching { DanmakuEndpoint(it) }.isFailure)
        }
        assertTrue(runCatching { DanmakuEndpoint(server.url("/").toString(), official = true) }.isFailure)
    }

    @Test fun customSearchRetainsTokenPrefixAndOpaqueBangumiId() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"animes":[
            {"animeId":9223372036854775806,"bangumiId":"opaque/season","animeTitle":"title","startDate":"2023-01-01"}]}"""))
        val results = api("/token/").search("a b")
        assertEquals("opaque/season", results.single().id)
        assertEquals(2023, results.single().year)
        val request = server.takeRequest()
        assertEquals("/token/api/v2/search/anime?keyword=a%20b", request.path)
        assertNull(request.getHeader("X-AppId"))
        assertNull(request.getHeader("X-AppSecret"))
    }

    @Test fun detailsAcceptsNumericInt64IdsAndStringEpisodeNumbers() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"bangumi":{"animeId":9223372036854775806,
            "animeTitle":"show","episodes":[{"episodeId":9223372036854775805,"episodeTitle":"part",
            "episodeNumber":"SP1","seasonId":"season-a"}]}}"""))
        val result = api("/prefix/api/v2/").details("opaque/id")
        assertEquals("9223372036854775806", result.id)
        assertEquals(DanmakuEpisode("9223372036854775805", "part", "SP1", "season-a"), result.episodes.single())
        assertEquals("/prefix/api/v2/bangumi/opaque%2Fid", server.takeRequest().path)
    }

    @Test fun titleYearFallbackRequiresExplicitMatchingParentheses() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"animes":[
            {"animeId":1,"animeTitle":"作品(2023)【日番】from source"},
            {"animeId":2,"animeTitle":"作品（1999）"},
            {"animeId":3,"animeTitle":"1984"},
            {"animeId":4,"animeTitle":"作品(2023)","year":2024},
            {"animeId":5,"animeTitle":"作品(2023)","startDate":"2022-01-01"},
            {"animeId":6,"animeTitle":"作品(2023）"}]}"""))
        assertEquals(listOf(2023, 1999, null, 2024, 2022, null), api().search("作品").map { it.year })
    }

    @Test fun customCommentsExplicitlyAskForJsonAndBusinessErrorFails() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"comments":[]}"""))
        assertTrue(api().comments("123").isEmpty())
        assertEquals("/api/v2/comment/123?format=json", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("""{"success":false,"errorMessage":"token=secret","animes":[]}"""))
        val failure = runCatching { api().search("query") }.exceptionOrNull() as DanmakuException
        assertEquals(DanmakuFailure.UNAVAILABLE, failure.reason)
        assertFalse(failure.stackTraceToString().contains("secret"))
    }

    @Test fun officialWithoutCredentialsFailsBeforeNetwork() = runBlocking {
        val official = DanmakuApi(OkHttpClient(), DanmakuEndpoint(DanmakuEndpoint.OFFICIAL_URL, official = true))
        val failure = runCatching { official.comments("1") }.exceptionOrNull() as DanmakuException
        assertEquals(DanmakuFailure.UNAVAILABLE, failure.reason)
        assertEquals(0, server.requestCount)
    }

    @Test fun officialSignsOnlyFixedOriginAndIncludesRelatedComments() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https://api.dandanplay.net/api/v2/comment/1?withRelated=true", request.url.toString())
            val timestamp = request.header("X-Timestamp")!!
            val expected = MessageDigest.getInstance("SHA-256")
                .digest(("app" + timestamp + "/api/v2/comment/1" + "secret").toByteArray()).toByteString().base64()
            assertEquals(expected, request.header("X-Signature"))
            assertEquals("app", request.header("X-AppId"))
            assertNull(request.header("X-AppSecret"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"comments":[]}""".toResponseBody()).build()
        }.build()
        val official = DanmakuApi(http, DanmakuEndpoint(DanmakuEndpoint.OFFICIAL_URL,
            official = true, DanmakuCredentials("app", "secret")))
        assertTrue(official.comments("1").isEmpty())
    }
}

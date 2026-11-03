package com.hesier.lumy.net.danmaku

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class DanmakuProxyTest {
    private val token = "test-proxy-token-0123456789abcdef"
    private fun endpoint(url: String = "https://relay.example.test/prefix") =
        DanmakuEndpoint(url, official = true, proxyToken = token)

    @Test fun proxyRequiresHttpsOfficialModeAndSeparateCredentials() {
        listOf("http://relay.example.test", "https://user:secret@relay.example.test",
            "https://relay.example.test?token=secret", "https://relay.example.test#fragment").forEach {
            assertTrue(runCatching { endpoint(it) }.isFailure)
        }
        assertTrue(runCatching { DanmakuEndpoint("https://relay.example.test", proxyToken = token) }.isFailure)
        assertTrue(runCatching { endpoint().copy(proxyToken = "short") }.isFailure)
        assertTrue(runCatching { endpoint().copy(proxyToken = token + "\n") }.isFailure)
        assertTrue(runCatching { endpoint().copy(credentials = DanmakuCredentials("id", "secret")) }.isFailure)
    }

    @Test fun proxyKeepsOfficialIdentityAcrossHostsAndTokenRotation() {
        val direct = DanmakuEndpoint(DanmakuEndpoint.OFFICIAL_URL, official = true)
        assertEquals(direct.identity, endpoint().identity)
        assertEquals(direct.identity, endpoint("https://other.example.test").copy(proxyToken = token.reversed()).identity)
        assertFalse(endpoint().toString().contains(token))
        assertFalse(endpoint().toString().contains("relay.example.test"))
        assertNotEquals(endpoint().identity, DanmakuEndpoint("https://relay.example.test/prefix").identity)
    }

    @Test fun proxySendsBearerWithoutOfficialSigningAndRetainsPrefix() = runBlocking {
        val paths = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            paths += request.url.encodedPath
            assertEquals("Bearer $token", request.header("Authorization"))
            listOf("X-AppId", "X-AppSecret", "X-Signature", "X-Timestamp", "X-Emby-Token").forEach {
                assertNull(request.header(it))
            }
            val body = when {
                request.url.encodedPath.endsWith("/match") -> {
                    val content = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    assertTrue(content.contains("\"matchMode\":\"fileNameOnly\""))
                    """{"success":true,"isMatched":false,"matches":[]}"""
                }
                request.url.encodedPath.contains("/comment/") -> {
                    assertEquals("true", request.url.queryParameter("withRelated"))
                    """{"comments":[]}"""
                }
                else -> """{"success":true,"animes":[]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val api = DanmakuApi(http, endpoint())
        assertTrue(api.search("show").isEmpty())
        assertNull(api.match("show S01E01"))
        assertTrue(api.comments("1").isEmpty())
        assertEquals(listOf("/prefix/api/v2/search/anime", "/prefix/api/v2/match", "/prefix/api/v2/comment/1"), paths)
    }

    @Test fun proxyTokenIsRemovedOnCrossOriginRedirect() = runBlocking {
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("Test")
            if (requests++ == 0) {
                assertEquals("Bearer $token", request.header("Authorization"))
                response.code(302).header("Location", "https://cdn.example.test/comments")
                    .body("".toResponseBody()).build()
            } else {
                assertEquals("cdn.example.test", request.url.host)
                assertNull(request.header("Authorization"))
                response.code(200).body("""{"comments":[]}""".toResponseBody()).build()
            }
        }.build()
        assertTrue(DanmakuApi(http, endpoint()).comments("1").isEmpty())
        assertEquals(2, requests)
    }
}

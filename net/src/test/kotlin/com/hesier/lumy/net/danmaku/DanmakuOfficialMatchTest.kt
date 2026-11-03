package com.hesier.lumy.net.danmaku

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class DanmakuOfficialMatchTest {
    @Test fun officialParameterRejectionAllowsSearchFallback() = runBlocking {
        val paths = mutableListOf<String>()
        val api = api { path ->
            paths += path
            when (path) {
                "/api/v2/match" -> 200 to """{"success":false,"errorCode":2,"errorMessage":"private-data"}"""
                else -> 200 to """{"success":true,"animes":[{"animeId":1,"animeTitle":"show"}]}"""
            }
        }
        assertNull(api.match("show S01E01"))
        assertEquals("show", api.search("show").single().title)
        assertEquals(listOf("/api/v2/match", "/api/v2/search/anime"), paths)
    }

    @Test fun customParameterRejectionStillReportsFailure() = runBlocking {
        val api = api(official = false) { 200 to """{"success":false,"errorCode":2}""" }
        val error = runCatching { api.match("show S01E01") }.exceptionOrNull() as DanmakuException
        assertEquals(DanmakuFailure.UNAVAILABLE, error.reason)
    }

    @Test fun officialOtherBusinessFailuresAreNotTreatedAsNoMatch() = runBlocking {
        listOf("1", "null", "\"invalid\"").forEach { code ->
            val api = api { 200 to """{"success":false,"errorCode":$code,"errorMessage":"private-data"}""" }
            val error = runCatching { api.match("show S01E01") }.exceptionOrNull() as DanmakuException
            assertEquals(DanmakuFailure.UNAVAILABLE, error.reason)
            assertFalse(error.stackTraceToString().contains("private-data"))
        }
    }

    @Test fun officialHttpFailuresAreNotTreatedAsNoMatch() = runBlocking {
        mapOf(401 to DanmakuFailure.AUTH, 403 to DanmakuFailure.AUTH,
            429 to DanmakuFailure.RATE_LIMITED, 500 to DanmakuFailure.UNAVAILABLE).forEach { (code, expected) ->
            val api = api { code to """{"success":false,"errorCode":2}""" }
            val error = runCatching { api.match("show S01E01") }.exceptionOrNull() as DanmakuException
            assertEquals(expected, error.reason)
        }
    }

    private fun api(official: Boolean = true, reply: (String) -> Pair<Int, String>): DanmakuApi {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (code, body) = reply(request.url.encodedPath)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test")
                .body(body.toResponseBody()).build()
        }.build()
        val endpoint = if (official) DanmakuEndpoint(DanmakuEndpoint.OFFICIAL_URL,
            official = true, DanmakuCredentials("app", "secret")) else DanmakuEndpoint("https://example.com")
        return DanmakuApi(http, endpoint)
    }
}

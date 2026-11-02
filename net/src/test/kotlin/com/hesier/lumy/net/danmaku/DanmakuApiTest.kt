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

    @Test fun customMatchPreservesPrefixUnicodeBodyAndFirstNumericCandidate() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"isMatched":true,"matches":[
            {"animeId":2452047,"episodeId":11410},{"animeId":9,"episodeId":10}]}"""))
        assertEquals(DanmakuMatchCandidate("2452047", "11410"), api("/proxy/token/").match("三体 S01E01"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/proxy/token/api/v2/match", request.path)
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals("""{"fileName":"三体 S01E01"}""", request.body.readUtf8())
        listOf("Authorization", "X-Emby-Token", "X-AppId", "X-AppSecret", "X-Signature").forEach {
            assertNull(request.getHeader(it))
        }
    }

    @Test fun matchPreservesOpaqueIdsWithoutNormalizingAgainstDetailsIds() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"isMatched":true,
            "matches":[{"animeId":"2452047","episodeId":"opaque/episode"}]}"""))
        server.enqueue(MockResponse().setBody("""{"success":true,"bangumi":{
            "animeId":2452047,"bangumiId":"opaque-bangumi","animeTitle":"show","episodes":[]}}"""))
        val api = api("/prefix/api/v2/")
        val match = checkNotNull(api.match("show S01E01"))
        assertEquals(DanmakuMatchCandidate("2452047", "opaque/episode"), match)
        assertEquals("opaque-bangumi", api.details(match.animeId).id)
        assertEquals("/prefix/api/v2/match", server.takeRequest().path)
        assertEquals("/prefix/api/v2/bangumi/2452047", server.takeRequest().path)
    }

    @Test fun matchConvertsOptionalSignedFractionalShiftToMilliseconds() = runBlocking {
        mapOf<String?, Long>(null to 0, "null" to 0, "0" to 0, "1.5" to 1500, "-0.5" to -500,
            "0.0005" to 1, "-0.0005" to -1, "9223372036854775.807" to Long.MAX_VALUE,
            "-9223372036854775.808" to Long.MIN_VALUE).forEach { (shift, expected) ->
            val extra = shift?.let { ",\"shift\":$it" }.orEmpty()
            server.enqueue(MockResponse().setBody("""{"success":true,"matches":[{"animeId":1,"episodeId":2$extra}]}"""))
            assertEquals(DanmakuMatchCandidate("1", "2", expected), api().match("show S01E01"))
        }
    }

    @Test fun matchRejectsInvalidNonFiniteAndOverflowingShift() = runBlocking {
        listOf("\"1.5\"", "true", "{}", "[]", "NaN", "Infinity", "1e309",
            "9223372036854775.808", "-9223372036854775.809").forEach { shift ->
            server.enqueue(MockResponse().setBody("""{"matches":[{"animeId":1,"episodeId":2,"shift":$shift}]}"""))
            val error = runCatching { api().match("show S01E01") }.exceptionOrNull() as DanmakuException
            assertEquals(DanmakuFailure.INVALID_RESPONSE, error.reason)
        }
    }

    @Test fun inexactMatchStillReturnsTheFirstCandidate() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"isMatched":false,"matches":[
            {"animeId":"first-anime","episodeId":"first-episode"},
            {"animeId":"second-anime","episodeId":"second-episode"}]}"""))
        assertEquals(DanmakuMatchCandidate("first-anime", "first-episode"), api().match("show S01E01"))
    }

    @Test fun matchSupportsInt64IdsAndEscapesSyntheticTitle() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"isMatched":true,
            "matches":[{"animeId":9223372036854775806,"episodeId":9223372036854775805}]}"""))
        assertEquals(DanmakuMatchCandidate("9223372036854775806", "9223372036854775805"),
            api().match("三体 \"special\" S01E01"))
        val json = kotlinx.serialization.json.Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            as kotlinx.serialization.json.JsonObject
        assertEquals("三体 \"special\" S01E01", json.getValue("fileName").text())
    }

    @Test fun matchReturnsNullForNoMatchAndOptionalUnsupportedStatuses() = runBlocking {
        listOf("""{"success":true,"isMatched":false,"matches":[]}""",
            """{"success":true,"isMatched":false,"matches":null}""",
            """{"success":true,"isMatched":false}""", """{"success":true}""",
            """{"success":true,"matches":null}""",
            """{"success":true,"isMatched":true,"matches":[]}""").forEach { body ->
            server.enqueue(MockResponse().setBody(body))
            assertNull(api().match("show S01E01"))
        }
        listOf(404, 405, 501).forEach { code ->
            server.enqueue(MockResponse().setResponseCode(code).setBody("not supported"))
            assertNull(api().match("show S01E01"))
        }
    }

    @Test fun malformedNonEmptyMatchNeverSelectsALaterCandidate() = runBlocking {
        listOf("""{"matches":[{} ,{"animeId":1,"episodeId":2}]}""", """{"matches":[null]}""",
            """{"matches":[{"animeId":true,"episodeId":2}]}""",
            """{"matches":[{"animeId":1,"episodeId":1.5}]}""",
            """{"matches":[{"animeId":"","episodeId":2}]}""", """{"matches":{}}""",
            """{"isMatched":"invalid","matches":[{"animeId":1,"episodeId":2}]}""",
            """{"success":true,"isMatched":true}""", """{"success":true,"isMatched":true,"matches":null}""",
            """{"success":true,"isMatched":false,"matches":{}}""", "not JSON").forEach { body ->
            server.enqueue(MockResponse().setBody(body))
            val error = runCatching { api().match("show S01E01") }.exceptionOrNull() as DanmakuException
            assertEquals(DanmakuFailure.INVALID_RESPONSE, error.reason)
        }
    }

    @Test fun matchRetainsBusinessAndHttpFailuresWithoutLeakingPrivateData() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":false,"errorMessage":"private-token","matches":[]}"""))
        val business = runCatching { api("/private-token/").match("show S01E01") }.exceptionOrNull() as DanmakuException
        assertEquals(DanmakuFailure.UNAVAILABLE, business.reason)
        assertFalse(business.stackTraceToString().contains("private-token"))
        mapOf(401 to DanmakuFailure.AUTH, 429 to DanmakuFailure.RATE_LIMITED, 500 to DanmakuFailure.UNAVAILABLE)
            .forEach { (code, expected) ->
                server.enqueue(MockResponse().setResponseCode(code))
                val failure = runCatching { api().match("show S01E01") }.exceptionOrNull() as DanmakuException
                assertEquals(expected, failure.reason)
            }
    }

    @Test fun officialMatchReusesTheFixedOriginSignatureWithoutExposingTheSecret() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("POST", request.method)
            assertEquals("https://api.dandanplay.net/api/v2/match", request.url.toString())
            val buffer = okio.Buffer()
            request.body!!.writeTo(buffer)
            assertEquals("""{"fileName":"show S01E01","matchMode":"fileNameOnly"}""", buffer.readUtf8())
            val timestamp = request.header("X-Timestamp")!!
            val expected = MessageDigest.getInstance("SHA-256")
                .digest(("app" + timestamp + "/api/v2/match" + "secret").toByteArray()).toByteString().base64()
            assertEquals(expected, request.header("X-Signature"))
            assertEquals("app", request.header("X-AppId"))
            assertNull(request.header("X-AppSecret"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"success":true,"isMatched":false,"matches":[
                    {"animeId":1,"episodeId":2},{"animeId":3,"episodeId":4}]}""".toResponseBody()).build()
        }.build()
        val official = DanmakuApi(http, DanmakuEndpoint(DanmakuEndpoint.OFFICIAL_URL,
            official = true, DanmakuCredentials("app", "secret")))
        assertEquals(DanmakuMatchCandidate("1", "2"), official.match("show S01E01"))
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

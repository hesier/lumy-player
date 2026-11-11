package com.hesier.lumy.plex

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PlexHomeAccountTest {
    private val web = MockWebServer()
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val original = chain.request()
        val url = web.url(original.url.encodedPath).newBuilder()
            .encodedQuery(original.url.encodedQuery).build()
        chain.proceed(original.newBuilder().url(url).build())
    }.build()
    private val account = PlexAccount(PlexClient(PlexClientInfo("Test", "1", "id", "12", "Emu"), http))

    @After
    fun tearDown() = web.shutdown()

    @Test
    fun `home users parse flags names and optional avatars`() = runBlocking {
        respond("""
            <MediaContainer size="3">
              <User id="12" title="Parent &amp; owner" protected="1" admin="1" restricted="0" thumb="https://plex.tv/avatar"/>
              <User id="13" title="Child" protected="0" admin="0" restricted="1"/>
              <User id="14" title="Other" protected="true" admin="false" restricted="true" thumb=""/>
            </MediaContainer>
        """.trimIndent())
        val users = account.homeUsers("account-token")
        assertEquals(3, users.size)
        assertEquals(PlexHomeUser("12", "Parent & owner", "https://plex.tv/avatar", true, true, false), users[0])
        assertEquals(PlexHomeUser("13", "Child", restricted = true), users[1])
        assertTrue(users[2].protected)
        assertFalse(users[2].admin)
        assertNull(users[2].thumb)
        val request = web.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/home/users", request.path)
        assertEquals("application/xml", request.getHeader("Accept"))
        assertEquals("account-token", request.getHeader("X-Plex-Token"))
    }

    @Test
    fun `switch without pin returns selected users account token`() = runBlocking {
        respond("""<user id="13" authenticationToken="selected-account-token"/>""")
        assertEquals("selected-account-token", account.switchHomeUser("owner-token", "13"))
        val request = web.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/home/users/13/switch", request.path)
        assertEquals("owner-token", request.getHeader("X-Plex-Token"))
        assertEquals("application/xml", request.getHeader("Accept"))
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `switch encodes optional pin without converting leading zeroes`() = runBlocking {
        respond("""<user authenticationToken="new-token"/>""")
        assertEquals("new-token", account.switchHomeUser("owner-token", "13", "0123"))
        assertEquals("0123", web.takeRequest().requestUrl!!.queryParameter("pin"))
    }

    @Test
    fun `invalid pin preserves status without disclosing pin or tokens`() {
        web.enqueue(MockResponse().setResponseCode(401).setBody("bad-pin owner-token secret-response-token"))
        val error = assertThrows(PlexHttpException::class.java) {
            runBlocking { account.switchHomeUser("owner-token", "13", "bad-pin") }
        }
        assertEquals(401, error.code)
        assertEquals("HTTP 401 /api/home/users/13/switch", error.message)
    }

    @Test
    fun `missing or blank switched token fails instead of retaining owner token`() {
        listOf("<user/>", """<user authenticationToken=" "/>""").forEach { body ->
            respond(body)
            assertThrows(IOException::class.java) {
                runBlocking { account.switchHomeUser("owner-token", "13") }
            }
        }
    }

    @Test
    fun `empty home is returned as empty list`() = runBlocking {
        respond("""<MediaContainer size="0"/>""")
        assertEquals(emptyList<PlexHomeUser>(), account.homeUsers("owner-token"))
    }

    @Test
    fun `external XML resources are rejected`() {
        respond("""<!DOCTYPE MediaContainer SYSTEM "http://127.0.0.1/external"><MediaContainer/>""")
        assertThrows(org.xml.sax.SAXException::class.java) {
            runBlocking { account.homeUsers("owner-token") }
        }
    }

    private fun respond(body: String) {
        web.enqueue(MockResponse().setHeader("Content-Type", "application/xml").setBody(body))
    }
}

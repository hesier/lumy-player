package com.hesier.lumy.fnos

import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest

class FnOsNativeClientTest {
    private val web = MockWebServer()
    private val foreign = MockWebServer()
    private val client = FnOsClient(FnOsClientInfo("Test", "1", "test-device", "Test"))

    @After
    fun close() {
        web.shutdown()
        foreign.shutdown()
    }

    private fun native() = FnOsNativeClient(
        ServerSession(ServerKind.FNOS, "server", "Test", web.url("/").toString().trimEnd('/'), "test-token"),
        client,
    )

    private fun bootstrap() {
        web.enqueue(MockResponse().setBody("""<script src="/v/assets/entry.js"></script>"""))
        web.enqueue(MockResponse().setBody("""import{sign}from"./sign.js";"""))
        web.enqueue(MockResponse().setBody(FIXTURE))
    }

    @Test
    fun `bootstrap stays anonymous and get signs sorted decoded query with original path`() = runBlocking {
        bootstrap()
        web.enqueue(MockResponse().setBody("""{"code":0,"data":{"value":1}}"""))
        val result = native().get("sys/config?z=hello%20world&a=%E4%B8%AD&empty=&skip=null&a=null")
        assertEquals(JsonObject(mapOf("value" to JsonPrimitive(1))), result)
        repeat(3) {
            val request = web.takeRequest()
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("authx"))
        }
        val request = web.takeRequest()
        assertEquals("/v/api/v1/sys/config", request.requestUrl!!.encodedPath)
        assertEquals("test-token", request.getHeader("Authorization"))
        assertEquals("web", request.getHeader("X-Trim-Client"))
        assertEquals("42", request.getHeader("X-Trim-Client-Version"))
        checkSignature(request, "a=中&empty=&z=hello world")
    }

    @Test
    fun `post signs exact JSON body and protocol loads once`() = runBlocking {
        bootstrap()
        repeat(2) { web.enqueue(MockResponse().setBody("""{"code":0,"data":true}""")) }
        val native = native()
        val body = JsonObject(linkedMapOf("label" to JsonPrimitive("中文"), "position" to JsonPrimitive(12)))
        native.post("api/v1/play/record", body)
        native.get("/v/api/v1/user/info")
        repeat(3) { web.takeRequest() }
        val request = web.takeRequest()
        assertEquals("POST", request.method)
        assertEquals(body.toString(), request.body.clone().readUtf8())
        checkSignature(request, body.toString())
        assertEquals("/v/api/v1/user/info", web.takeRequest().requestUrl!!.encodedPath)
        assertEquals(5, web.requestCount)
    }

    @Test
    fun `delete sends and signs exact JSON body`() = runBlocking {
        bootstrap()
        web.enqueue(MockResponse().setBody("""{"code":0,"data":true}"""))
        val body = JsonObject(mapOf("item_guid" to JsonPrimitive("canonical-item")))
        native().delete("item/watched", body)
        repeat(3) { web.takeRequest() }
        val request = web.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v/api/v1/item/watched", request.requestUrl!!.encodedPath)
        assertEquals(body.toString(), request.body.clone().readUtf8())
        assertEquals("test-token", request.getHeader("Authorization"))
        checkSignature(request, body.toString())
    }

    @Test
    fun `business error becomes IOException`() = runBlocking {
        bootstrap()
        web.enqueue(MockResponse().setBody("""{"code":1003,"msg":"invalid sign"}"""))
        try {
            native().get("user/info")
            fail("Expected business error")
        } catch (error: IOException) {
            assertTrue(error.message!!.contains("1003"))
            assertTrue(!error.message!!.contains("test-token"))
        }
    }

    @Test
    fun `absolute and escaping API paths are rejected before authentication`() = runBlocking {
        val native = native()
        for (path in listOf(foreign.url("/v/api/v1/user/info").toString(), "../user/info", "/user/info", "user/info#token")) {
            try {
                native.get(path)
                fail("Expected rejected URL")
            } catch (_: IOException) {
                // Invalid paths must never send the session token.
            }
        }
        assertEquals(0, web.requestCount)
        assertEquals(0, foreign.requestCount)
    }

    @Test
    fun `cross origin scripts are ignored and API redirects cannot leak authentication`() = runBlocking {
        web.enqueue(MockResponse().setBody("""<script src="${foreign.url("/sign.js")}"></script><script src="/v/sign.js"></script>"""))
        web.enqueue(MockResponse().setBody(FIXTURE))
        web.enqueue(MockResponse().setResponseCode(302).setHeader("Location", foreign.url("/capture")))
        try {
            native().get("user/info")
            fail("Expected redirect failure")
        } catch (_: IOException) {
            // Redirects are disabled for bootstrap and authenticated requests.
        }
        assertEquals(3, web.requestCount)
        assertEquals(0, foreign.requestCount)
    }

    private fun checkSignature(request: RecordedRequest, content: String) {
        val auth = request.getHeader("authx")!!.split('&').associate {
            val (key, value) = it.split('=', limit = 2)
            key to value
        }
        assertTrue(auth["nonce"]!!.matches(Regex("[1-9][0-9]{5}")))
        val input = listOf("fixture-app", request.requestUrl!!.encodedPath, auth["nonce"]!!,
            auth["timestamp"]!!, md5(content), "fixture-key").joinToString("_")
        assertEquals(md5(input), auth["sign"])
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        // Anonymous fixture has the same structure as the server's public module.
        private val FIXTURE = """
            var demo=(()=>{let e=new Uint8Array([103,104,121,117,116,115,100,44,106,100,120]),t=new Uint8Array(11);for(let n=0;n<11;n++)t[n]=e[n]^1;return new TextDecoder(`utf-8`).decode(t)})();
            var versionHeader=`X-Trim-Client-Version`,headers={[versionHeader]:`42`};
            var sign=(r,s,c,o,t)=>{let l=[`fixture-app`,r,s,c,o,t].join(`_`);return `nonce=signature`;};
            client({apiKey:demo,header:`authx`});
        """.trimIndent()
    }
}

package com.hesier.lumy.net

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class UpdateClientTest {
    @get:Rule val temp = TemporaryFolder()
    private val server = MockWebServer().apply { start() }
    private val github = server.url("/").toString().trimEnd('/')
    private val client = UpdateClient(OkHttpClient(), "owner/repo", github)
    private val apk = ByteArray(200_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val info = UpdateInfo(5, "0.3.0", "app-v0.3.0.apk", apk.size.toLong(), sha)

    @After fun tearDown() = server.shutdown()

    @Test fun mirrorPrefix() {
        val url = "https://github.com/a/b"
        assertEquals(url, UpdateClient.withMirror(url, null))
        assertEquals(url, UpdateClient.withMirror(url, "  "))
        assertEquals("https://ghfast.top/$url", UpdateClient.withMirror(url, "https://ghfast.top"))
        assertEquals("https://ghfast.top/$url", UpdateClient.withMirror(url, "https://ghfast.top//"))
        assertEquals("https://ghfast.top/$url", UpdateClient.withMirror(url, "ghfast.top"))
        assertEquals("http://mirror.lan:8080/$url", UpdateClient.withMirror(url, "http://mirror.lan:8080/"))
    }

    @Test fun latestParsesInfoAndIgnoresUnknownFields() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"versionCode":5,"versionName":"0.3.0","apk":"a.apk","size":10,"sha256":"ab","notes":{"en":"Hi","zh":"你好"},"extra":1}""",
        ))
        val latest = client.latest(null)
        assertEquals(5, latest.versionCode)
        assertEquals("你好", latest.notes["zh"])
        assertEquals("/owner/repo/releases/latest/download/update.json", server.takeRequest().path)
    }

    @Test fun latestThroughMirror() = runTest {
        server.enqueue(MockResponse().setBody("""{"versionCode":5,"versionName":"0.3.0","apk":"a.apk","size":10,"sha256":"ab"}"""))
        // 镜像本身就是 MockWebServer：请求路径是完整的原地址
        client.latest(server.url("/").toString())
        assertEquals("/$github/owner/repo/releases/latest/download/update.json", server.takeRequest().path)
    }

    @Test(expected = java.io.IOException::class)
    fun latestFailsOnHttpError() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        client.latest(null)
    }

    @Test fun downloadVerifiesAndReportsProgress() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val target = temp.root.resolve("update/lumy.apk")
        var last = 0L
        client.download(info, null, target) { done, total ->
            assertEquals(apk.size.toLong(), total)
            last = done
        }
        assertArrayEquals(apk, target.readBytes())
        assertEquals(apk.size.toLong(), last)
        assertEquals("/owner/repo/releases/download/v0.3.0/app-v0.3.0.apk", server.takeRequest().path)
    }

    @Test fun downloadRejectsWrongContent() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(apk.copyOf().also { it[0] = 99 })))
        val target = temp.root.resolve("lumy.apk")
        try {
            client.download(info, null, target) { _, _ -> }
            fail("expected checksum failure")
        } catch (_: UpdateChecksumException) {
        }
        assertFalse(target.exists())
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test fun downloadRejectsTruncatedFile() = runTest {
        // 镜像返回的错误页面也会落到这里
        server.enqueue(MockResponse().setBody("<html>blocked</html>"))
        try {
            client.download(info, null, temp.root.resolve("lumy.apk")) { _, _ -> }
            fail("expected size failure")
        } catch (_: UpdateChecksumException) {
        }
    }
}

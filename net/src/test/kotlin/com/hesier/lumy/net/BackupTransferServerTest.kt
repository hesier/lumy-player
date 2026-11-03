package com.hesier.lumy.net

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class BackupTransferServerTest {
    private val uploaded = LinkedBlockingQueue<ByteArray>()
    private val payload = byteArrayOf(0, 1, 13, 10, -1, -128)

    private fun server(download: ByteArray? = null, lifetime: Long = 300_000) = BackupTransferServer(
        InetAddress.getByName("127.0.0.1"), "<html>backup</html>", download,
        { uploaded.offer(it) }, lifetime,
    )

    private data class Response(val headers: String, val body: ByteArray) {
        val status: Int get() = headers.substringAfter(' ').substringBefore(' ').toInt()
    }

    private fun request(
        server: BackupTransferServer,
        method: String = "POST",
        headers: Map<String, String?> = emptyMap(),
        body: ByteArray = payload,
        path: String = "/backup",
        extra: String = "",
    ): Response {
        val uri = URI(server.url)
        val fields = linkedMapOf<String, String?>(
            "Host" to uri.authority, "Origin" to server.url.substringBefore("/#"),
            "X-Lumy-Token" to uri.fragment, "Content-Type" to "application/octet-stream",
            "Content-Length" to body.size.toString(),
        ).apply { putAll(headers) }
        return Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 7000
            val text = "$method $path HTTP/1.1\r\n" + fields.entries
                .filter { it.value != null }.joinToString("") { "${it.key}: ${it.value}\r\n" } + extra + "\r\n"
            socket.getOutputStream().apply { write(text.toByteArray()); write(body); flush() }
            readResponse(socket)
        }
    }

    private fun readResponse(socket: Socket): Response {
        val input = socket.getInputStream()
        val headers = StringBuilder()
        while (!headers.endsWith("\r\n\r\n")) {
            val char = input.read()
            require(char >= 0)
            headers.append(char.toChar())
        }
        val length = headers.lineSequence().first { it.startsWith("Content-Length:") }
            .substringAfter(':').trim().toInt()
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            require(count > 0)
            offset += count
        }
        return Response(headers.toString(), body)
    }

    @Test fun `page is public and does not expose token`() = server().use { s ->
        val response = request(s, method = "GET", path = "/", headers = mapOf("X-Lumy-Token" to null))
        assertEquals(200, response.status)
        assertEquals("<html>backup</html>", response.body.toString(Charsets.UTF_8))
        assertTrue(response.headers.contains("Cache-Control: no-store"))
        assertTrue(response.headers.contains("Referrer-Policy: no-referrer"))
        assertFalse(response.headers.contains(URI(s.url).fragment))
        assertFalse(response.body.toString(Charsets.UTF_8).contains(URI(s.url).fragment))
    }

    @Test fun `downloads require token and valid host and origin`() = server(payload).use { s ->
        for (headers in listOf(
            mapOf("X-Lumy-Token" to "wrong"), mapOf("X-Lumy-Token" to null),
            mapOf("Origin" to "https://evil.example"), mapOf("Host" to "evil.example"),
        )) assertEquals(403, request(s, method = "GET", headers = headers).status)
        repeat(2) {
            val response = request(s, method = "GET", headers = mapOf("Origin" to null))
            assertEquals(200, response.status)
            assertArrayEquals(payload, response.body)
            assertTrue(response.headers.contains("application/octet-stream"))
            assertTrue(Regex("""filename="Lumy-backup-\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.lumy"""").containsMatchIn(response.headers))
            assertTrue(response.headers.contains("Cache-Control: no-store"))
            assertTrue(s.isRunning)
        }
    }

    @Test fun `upload requires token origin host and binary content type`() = server().use { s ->
        for (headers in listOf(
            mapOf("X-Lumy-Token" to "wrong"), mapOf("Origin" to null),
            mapOf("Origin" to "https://evil.example"), mapOf("Host" to "evil.example"),
        )) assertEquals(403, request(s, headers = headers).status)
        assertEquals(400, request(s, headers = mapOf("Content-Type" to "text/plain")).status)
        assertTrue(uploaded.isEmpty())
        assertTrue(s.isRunning)
    }

    @Test fun `valid binary upload responds then consumes session once`() = server().use { s ->
        assertEquals(200, request(s).status)
        assertArrayEquals(payload, uploaded.poll(2, TimeUnit.SECONDS))
        assertFalse(s.isRunning)
        assertTrue(runCatching { request(s) }.isFailure)
        assertTrue(uploaded.isEmpty())
    }

    @Test fun `max sized upload is accepted`() = server().use { s ->
        val bytes = ByteArray(BackupTransferServer.MAX_BYTES) { (it % 256).toByte() }
        assertEquals(200, request(s, body = bytes).status)
        assertArrayEquals(bytes, uploaded.poll(2, TimeUnit.SECONDS))
    }

    @Test fun `bad lengths chunked and duplicate headers are rejected without consuming session`() = server().use { s ->
        for (size in listOf("0", (BackupTransferServer.MAX_BYTES + 1).toString(), "999999999999999")) {
            assertEquals(413, request(s, headers = mapOf("Content-Length" to size)).status)
        }
        for (size in listOf("-1", "+2", "abc", "1, 1")) {
            assertEquals(400, request(s, headers = mapOf("Content-Length" to size)).status)
        }
        assertEquals(400, request(s, headers = mapOf("Content-Length" to null)).status)
        assertEquals(400, request(s, extra = "Transfer-Encoding: chunked\r\n").status)
        assertEquals(400, request(s, extra = "Content-Length: 6\r\n").status)
        assertTrue(uploaded.isEmpty())
        assertEquals(200, request(s).status)
    }

    @Test fun `download sessions cannot upload and upload sessions have no download`() {
        server(payload).use { s -> assertEquals(405, request(s).status) }
        server().use { s -> assertEquals(404, request(s, method = "GET").status) }
        assertTrue(uploaded.isEmpty())
    }

    @Test fun `truncated body is rejected and session remains usable`() = server().use { s ->
        val uri = URI(s.url)
        Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 2000
            val headers = "POST /backup HTTP/1.1\r\nHost: ${uri.authority}\r\n" +
                "Origin: ${s.url.substringBefore("/#")}\r\nX-Lumy-Token: ${uri.fragment}\r\n" +
                "Content-Type: application/octet-stream\r\nContent-Length: 100\r\n\r\nabc"
            socket.getOutputStream().apply { write(headers.toByteArray()); flush() }
            socket.shutdownOutput()
            assertEquals(400, readResponse(socket).status)
        }
        assertTrue(uploaded.isEmpty())
        assertEquals(200, request(s).status)
    }

    @Test fun `oversized headers cannot monopolize receiver`() = server().use { s ->
        val uri = URI(s.url)
        Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 2000
            socket.getOutputStream().apply {
                write(("GET / HTTP/1.1\r\nX-Long: " + "a".repeat(8192)).toByteArray())
                flush()
            }
            assertEquals(400, readResponse(socket).status)
        }
        assertTrue(s.isRunning)
    }

    @Test fun `incomplete request times out without ending usable session`() = server().use { s ->
        val uri = URI(s.url)
        Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 7000
            socket.getOutputStream().apply { write("GET / HTTP/1.1\r\n".toByteArray()); flush() }
            val first = try { socket.getInputStream().read() } catch (_: SocketException) { -1 }
            assertTrue(first == -1 || first == 'H'.code)
        }
        assertTrue(s.isRunning)
        assertEquals(200, request(s).status)
    }

    @Test fun `expiry closes active incomplete request and releases listener`() = server(lifetime = 100).use { s ->
        val uri = URI(s.url)
        Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 2000
            socket.getOutputStream().apply { write("GET / HTTP/1.1\r\n".toByteArray()); flush() }
            assertEquals(-1, socket.getInputStream().read())
        }
        assertFalse(s.isRunning)
        assertTrue(runCatching { request(s) }.isFailure)
        assertTrue(uploaded.isEmpty())
    }

    @Test fun `closing aborts active requests and is idempotent`() {
        val s = server()
        val uri = URI(s.url)
        Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 2000
            socket.getOutputStream().apply { write("GET / HTTP/1.1\r\n".toByteArray()); flush() }
            s.close()
            s.close()
            assertFalse(s.isRunning)
        }
        assertTrue(runCatching { request(s) }.isFailure)
        assertTrue(uploaded.isEmpty())
    }
}

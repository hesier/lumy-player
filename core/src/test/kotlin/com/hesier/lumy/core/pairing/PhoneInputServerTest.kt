package com.hesier.lumy.core.pairing

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PhoneInputServerTest {
    private val received = LinkedBlockingQueue<PhoneInputServer.ServerInput>()
    private fun server(lifetime: Long = 300_000) = PhoneInputServer(
        InetAddress.getByName("127.0.0.1"), "<html>local page</html>", { received.offer(it) }, lifetime,
    )

    private fun request(server: PhoneInputServer, method: String = "POST", path: String = "/submit",
                        token: String = URI(server.url).fragment, origin: String? = server.url.substringBefore("/#"),
                        body: String = "address=example.com&username=user&password=&name=",
                        length: Int = body.toByteArray().size, extra: String = ""): String {
        val uri = URI(server.url)
        return Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 3000
            val headers = "$method $path HTTP/1.1\r\nHost: ${uri.authority}\r\n" +
                (origin?.let { "Origin: $it\r\n" } ?: "") +
                "X-Lumy-Token: $token\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: $length\r\n$extra\r\n"
            socket.getOutputStream().apply { write(headers.toByteArray()); write(body.toByteArray()); flush() }
            val reader = socket.getInputStream().bufferedReader()
            val response = StringBuilder()
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: error("Missing response headers")
                response.append(line).append("\r\n")
                if (line.startsWith("Content-Length:")) contentLength = line.substringAfter(':').trim().toInt()
                if (line.isEmpty()) break
            }
            repeat(contentLength) { response.append(reader.read().also { require(it >= 0) }.toChar()) }
            response.toString()
        }
    }

    @Test fun `page is local and not cacheable`() = server().use { s ->
        val response = request(s, method = "GET", path = "/")
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertTrue(response.contains("Cache-Control: no-store"))
        assertTrue(response.endsWith("<html>local page</html>"))
        assertFalse(response.contains(URI(s.url).fragment))
    }

    @Test fun `credentials preserve unicode punctuation whitespace and empty passwords`() = server().use { s ->
        fun enc(text: String) = URLEncoder.encode(text, "UTF-8")
        val password = " +&=密碼 \n"
        val response = request(s, body = "address=${enc("https://example.com/emby")}&username=${enc(" 用户 ")}&password=${enc(password)}&name=${enc("客厅")}")
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertEquals(PhoneInputServer.ServerInput("https://example.com/emby", "用户", password, "客厅"), received.poll(2, TimeUnit.SECONDS))
        assertFalse(s.isRunning)
        assertFalse(response.contains(password))
        assertTrue(runCatching { request(s) }.isFailure)
    }

    @Test fun `wrong token and foreign or absent origins cannot submit`() = server().use { s ->
        assertTrue(request(s, token = "wrong").startsWith("HTTP/1.1 403"))
        assertTrue(request(s, origin = "https://attacker.example").startsWith("HTTP/1.1 403"))
        assertTrue(request(s, origin = null).startsWith("HTTP/1.1 403"))
        assertTrue(received.isEmpty())
        assertTrue(request(s).startsWith("HTTP/1.1 200"))
    }

    @Test fun `bad inputs oversized bodies and duplicate fields can be retried`() = server().use { s ->
        for (body in listOf("address=ftp://example.com&username=u", "address=example.com&username=", "address=example.com&username=u&username=v", "address=%GG&username=u")) {
            assertTrue(request(s, body = body).startsWith("HTTP/1.1 400"))
        }
        assertTrue(request(s, length = 25000).startsWith("HTTP/1.1 413"))
        assertTrue(request(s, extra = "Transfer-Encoding: chunked\r\n").startsWith("HTTP/1.1 400"))
        assertTrue(received.isEmpty())
        assertTrue(request(s).startsWith("HTTP/1.1 200"))
    }

    @Test fun `expired code cannot accept credentials`() = server(lifetime = 0).use { s ->
        runCatching { request(s) }
        assertNull(received.poll(100, TimeUnit.MILLISECONDS))
    }

    @Test fun `closing receiver releases port and aborts incomplete request`() {
        val s = server()
        val uri = URI(s.url)
        Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().apply { write("POST /submit HTTP/1.1\r\n".toByteArray()); flush() }
            s.close()
            assertFalse(s.isRunning)
        }
        assertTrue(runCatching { request(s) }.isFailure)
        assertTrue(received.isEmpty())
    }
}

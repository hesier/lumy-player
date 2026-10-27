package com.hesier.lumy.net

import java.io.Closeable
import java.io.InputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/** Short-lived, single-use LAN receiver. Credentials never appear in URLs or responses. */
class PhoneInputServer(
    address: InetAddress,
    private val page: String,
    private val onReceived: (ServerInput) -> Unit,
    lifetimeMillis: Long = 300_000,
) : Closeable {
    data class ServerInput(val address: String, val username: String, val password: String, val name: String)

    private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val listener = ServerSocket(0, 4, address).apply { soTimeout = 1000 }
    private val origin = "http://${address.hostAddress}:${listener.localPort}"
    val url: String = "$origin/#$token"
    private val deadline = System.nanoTime() + lifetimeMillis * 1_000_000
    @Volatile private var closed = false
    @Volatile private var client: Socket? = null
    val isRunning: Boolean get() = !closed

    init {
        Thread({
            try {
                while (!closed && System.nanoTime() < deadline) {
                    val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                    client = socket
                    try {
                        socket.use {
                            it.soTimeout = 3000
                            handle(it)
                        }
                    } catch (_: Exception) {
                        // A malformed request or disconnected phone must not end the session.
                    } finally {
                        client = null
                    }
                }
            } catch (_: IOException) {
                // Closing the dialog interrupts accept().
            } finally {
                close()
            }
        }, "LumyPhoneInput").apply { isDaemon = true; start() }
    }

    private fun handle(socket: Socket) {
        val requestDeadline = minOf(deadline, System.nanoTime() + 5_000_000_000L)
        val input = object : java.io.FilterInputStream(socket.getInputStream()) {
            override fun read(): Int {
                require(!closed && System.nanoTime() < requestDeadline)
                return super.read()
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                require(!closed && System.nanoTime() < requestDeadline)
                return super.read(bytes, offset, length)
            }
        }
        var remaining = 8192
        fun line(): String {
            val out = StringBuilder()
            while (true) {
                require(remaining-- > 0 && !closed && System.nanoTime() < deadline)
                val c = input.read()
                require(c >= 0)
                if (c == 10) return out.toString().removeSuffix("\r")
                out.append(c.toChar())
            }
        }
        val request = line().split(' ')
        require(request.size == 3 && request[2] == "HTTP/1.1")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            require(colon > 0)
            val key = header.substring(0, colon).trim().lowercase(java.util.Locale.ROOT)
            require(!headers.containsKey(key))
            headers[key] = header.substring(colon + 1).trim()
        }
        if (closed || System.nanoTime() >= deadline) return respond(socket, 410)
        if (headers["host"] != origin.removePrefix("http://")) return respond(socket, 403)
        if (request[0] == "GET" && request[1] == "/") {
            return respond(socket, 200, page, "text/html; charset=utf-8")
        }
        if (request[0] != "POST" || request[1] != "/submit") return respond(socket, 404)
        if (headers["origin"] != origin || !MessageDigest.isEqual(
                headers["x-lumy-token"].orEmpty().toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8),
            )) return respond(socket, 403)
        if (headers["transfer-encoding"] != null ||
            headers["content-type"]?.substringBefore(';')?.trim() != "application/x-www-form-urlencoded"
        ) return respond(socket, 400)
        val size = headers["content-length"]?.toIntOrNull()
        if (size == null || size !in 1..24_576) return respond(socket, 413)
        val fields = try {
            val body = ByteArray(size)
            readFully(input, body)
            val pairs = String(body, Charsets.UTF_8).split('&').map {
                val parts = it.split('=', limit = 2)
                require(parts.size == 2)
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
            }
            require(pairs.map { it.first }.toSet().size == pairs.size)
            pairs.toMap()
        } catch (_: Exception) { return respond(socket, 400) }
        val address = fields["address"].orEmpty().trim()
        val username = fields["username"].orEmpty().trim()
        val password = fields["password"].orEmpty()
        val name = fields["name"].orEmpty().trim()
        val validAddress = runCatching {
            val uri = URI(if ("://" in address) address else "http://$address")
            uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null
        }.getOrDefault(false)
        if (!validAddress || address.length > 2048 || username.isEmpty() || username.length > 256 ||
            password.length > 4096 || name.length > 128
        ) return respond(socket, 400)
        if (closed || System.nanoTime() >= deadline) return respond(socket, 410)
        // Consume before invoking the UI, so queued requests cannot overwrite the first submission.
        closed = true
        listener.close()
        try { respond(socket, 200) } finally { onReceived(ServerInput(address, username, password, name)) }
    }

    private fun respond(socket: Socket, status: Int, body: String = "", type: String = "text/plain") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status Response\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Connection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
            "X-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n" +
            "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
            "connect-src 'self'; form-action 'none'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }

    override fun close() {
        closed = true
        runCatching { listener.close() }
        runCatching { client?.close() }
    }

    private fun readFully(input: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            require(count > 0)
            offset += count
        }
    }
}

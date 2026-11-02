package com.hesier.lumy.net

import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

/** Bounded LAN transfer session. The URL fragment never reaches HTTP request logs. */
class BackupTransferServer(
    address: InetAddress,
    private val page: String,
    private val download: ByteArray? = null,
    private val onUploaded: (ByteArray) -> Unit = {},
    lifetimeMillis: Long = 300_000,
) : Closeable {
    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        private const val MAX_HEADERS = 8192
        private const val REQUEST_MILLIS = 5000L
    }

    private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val lifetime = lifetimeMillis.also { require(it >= 0) { "Invalid transfer lifetime" } }
    private val validatedDownload = download.also {
        require(it == null || it.size <= MAX_BYTES) { "Backup exceeds transfer limit" }
    }
    private val listener = ServerSocket(0, 4, address).apply { soTimeout = 1000 }
    private val host = address.hostAddress.let { if (':' in it) "[$it]" else it }
    private val origin = "http://$host:${listener.localPort}"
    val url: String = "$origin/#$token"
    private val timer = Timer("LumyBackupExpiry", true)
    @Volatile private var closed = false
    @Volatile private var consumed = false
    @Volatile private var client: Socket? = null
    val isRunning: Boolean get() = !closed && !consumed

    init {
        timer.schedule(object : TimerTask() { override fun run() = close() }, lifetime)
        Thread({ serve() }, "LumyBackupTransfer").apply { isDaemon = true; start() }
    }

    private fun serve() {
        try {
            while (isRunning) {
                val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                client = socket
                val timeout = object : TimerTask() {
                    override fun run() { runCatching { socket.close() } }
                }
                try {
                    timer.schedule(timeout, REQUEST_MILLIS)
                    socket.use { if (isRunning) handle(it) }
                } catch (_: Exception) {
                    // Invalid clients must not invalidate a usable session.
                } finally {
                    timeout.cancel()
                    runCatching { socket.close() }
                    client = null
                }
            }
        } catch (_: Exception) {
            // Closing or expiry interrupts accept().
        } finally {
            close()
        }
    }

    private fun handle(socket: Socket) {
        val deadline = System.nanoTime() + REQUEST_MILLIS * 1_000_000
        val input = socket.getInputStream()
        val request = try { readRequest(socket, input, deadline) } catch (_: Exception) {
            return respond(socket, 400)
        }
        val (method, path, headers) = request
        if (!isRunning) return respond(socket, 410)
        if (headers["host"] != origin.removePrefix("http://")) return respond(socket, 403)
        if (headers["transfer-encoding"] != null) return respond(socket, 400)
        if (method == "GET" && path == "/") {
            return respond(socket, 200, page.toByteArray(Charsets.UTF_8), "text/html; charset=utf-8")
        }
        if (path != "/backup" || method !in listOf("GET", "POST")) return respond(socket, 404)
        val requestOrigin = headers["origin"]
        if ((requestOrigin != null && requestOrigin != origin) ||
            (method == "POST" && requestOrigin == null) || !MessageDigest.isEqual(
                token.toByteArray(Charsets.US_ASCII), headers["x-lumy-token"].orEmpty().toByteArray(Charsets.UTF_8),
            )
        ) return respond(socket, 403)
        if (method == "GET") {
            val bytes = validatedDownload ?: return respond(socket, 404)
            return respond(socket, 200, bytes, "application/octet-stream", attachment = true)
        }
        receive(socket, input, headers, deadline)
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>)

    private fun readRequest(socket: Socket, input: InputStream, deadline: Long): Request {
        var remaining = MAX_HEADERS
        fun line(): String {
            val result = StringBuilder()
            while (true) {
                require(remaining-- > 0)
                socket.soTimeout = remainingMillis(deadline)
                val char = input.read()
                require(char >= 0)
                if (char == 10) {
                    require(result.endsWith("\r"))
                    return result.dropLast(1).toString()
                }
                require(char == 13 || char in 32..126)
                result.append(char.toChar())
            }
        }
        val start = line().split(' ')
        require(start.size == 3 && start[2] == "HTTP/1.1")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            require(colon > 0)
            val key = header.substring(0, colon).lowercase(Locale.ROOT)
            require(key.all { it in 'a'..'z' || it in '0'..'9' || it == '-' })
            require(headers.put(key, header.substring(colon + 1).trim()) == null)
        }
        return Request(start[0], start[1], headers)
    }

    private fun receive(socket: Socket, input: InputStream, headers: Map<String, String>, deadline: Long) {
        if (download != null) return respond(socket, 405)
        if (headers["content-type"]?.substringBefore(';')?.trim() != "application/octet-stream") {
            return respond(socket, 400)
        }
        val length = headers["content-length"]
        if (length.isNullOrEmpty() || !length.all { it in '0'..'9' }) return respond(socket, 400)
        val size = length.toIntOrNull()
        if (size == null || size !in 1..MAX_BYTES) return respond(socket, 413)
        val bytes = ByteArray(size)
        try {
            var offset = 0
            while (offset < size) {
                socket.soTimeout = remainingMillis(deadline)
                val count = input.read(bytes, offset, size - offset)
                require(count > 0)
                offset += count
            }
        } catch (_: Exception) { return respond(socket, 400) }
        if (!isRunning) return respond(socket, 410)
        consumed = true
        listener.close()
        try { respond(socket, 200) } finally { onUploaded(bytes) }
    }

    private fun remainingMillis(deadline: Long): Int {
        require(!closed)
        val remaining = deadline - System.nanoTime()
        require(remaining > 0)
        return ((remaining / 1_000_000) + 1).toInt()
    }

    private fun respond(
        socket: Socket,
        status: Int,
        bytes: ByteArray = byteArrayOf(),
        type: String = "text/plain",
        attachment: Boolean = false,
    ) {
        val disposition = if (attachment) "Content-Disposition: attachment; filename=\"Lumy-backup.lumy\"\r\n" else ""
        val headers = "HTTP/1.1 $status Response\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            disposition + "Connection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
            "X-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n" +
            "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
            "connect-src 'self'; form-action 'none'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }

    override fun close() {
        closed = true
        timer.cancel()
        runCatching { listener.close() }
        runCatching { client?.close() }
    }
}

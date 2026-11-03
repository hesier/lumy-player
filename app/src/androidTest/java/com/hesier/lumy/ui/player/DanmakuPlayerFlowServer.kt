package com.hesier.lumy.ui.player

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/** Owned loopback responses exercise the real repository and remote-control UI. */
internal class DanmakuPlayerFlowServer : AutoCloseable {
    private val socket = ServerSocket(0)
    val endpoint = "http://127.0.0.1:${socket.localPort}"
    val paths = CopyOnWriteArrayList<String>()
    @Volatile var failComments = false
    @Volatile var matchBody: String? = null
    @Volatile var ambiguous = false
    private val worker = Thread {
        while (!socket.isClosed) runCatching { socket.accept().use(::respond) }
    }.apply { isDaemon = true; start() }

    fun commentRequests() = paths.count { "/comment/" in it }

    private fun respond(connection: Socket) {
        val input = DataInputStream(connection.getInputStream())
        val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return
        var length = 0
        var header = input.readLine()
        while (!header.isNullOrEmpty()) {
            if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
            header = input.readLine()
        }
        check(length in 0..8_192)
        input.readFully(ByteArray(length))
        paths.add(path)
        Thread.sleep(250)
        val decoded = URLDecoder.decode(path, "UTF-8")
        val (status, body) = when {
            path == "/plugin" -> 200 to """<i><d p="1,1,25,16777215,0,0,user,1">Plugin one</d><d p="2,1,25,16777215,0,0,user,2">Plugin two</d></i>"""
            "/match" in path -> if (matchBody == null) 404 to "{}" else 200 to checkNotNull(matchBody)
            "/search/anime" in path -> 200 to search(if ("S01E02" in decoded) emptyList() else listOf("A", "B"))
            "/bangumi/" in path -> 200 to details(path.substringAfterLast('/'))
            "/comment/" in path -> if (failComments) 500 to "{}" else 200 to comments(path.substringAfterLast('/'))
            else -> 404 to "{}"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        connection.getOutputStream().apply {
            write("HTTP/1.1 $status Result\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
    }

    private fun search(ids: List<String>): String {
        val rows = JSONArray()
        ids.forEach { rows.put(JSONObject().put("animeId", it).put("animeTitle", title(it))) }
        return JSONObject().put("success", true).put("animes", rows).toString()
    }

    private fun details(id: String): String {
        val episodes = JSONArray()
        for (number in 1..3) episodes.put(JSONObject().put("episodeId", "$id$number")
            .put("episodeTitle", "Flow $id episode $number").put("episodeNumber", number).put("seasonId", "opaque"))
        return JSONObject().put("success", true).put("bangumi", JSONObject()
            .put("animeId", id).put("animeTitle", title(id)).put("episodes", episodes)).toString()
    }

    private fun title(id: String) = if (id == "A" || ambiguous) "Flow Show" else "Flow $id"

    private fun comments(id: String): String {
        val rows = JSONArray()
        for (index in 0..2) rows.put(JSONObject().put("cid", "$id-$index")
            .put("p", "${1 + index * 0.6},1,16777215,user").put("m", "Flow comment $index"))
        return JSONObject().put("comments", rows).toString()
    }

    override fun close() { socket.close(); worker.join(2_000) }
}

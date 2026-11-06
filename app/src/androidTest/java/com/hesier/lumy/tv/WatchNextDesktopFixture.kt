package com.hesier.lumy.tv

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.hesier.lumy.core.HdrType
import com.hesier.lumy.core.Hub
import com.hesier.lumy.core.HubLabel
import com.hesier.lumy.core.HubStyle
import com.hesier.lumy.core.MediaDetail
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaPart
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.PlaybackRequest
import com.hesier.lumy.core.PlaybackSource
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import com.hesier.lumy.core.VideoStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Local playback and image fixtures with deliberately failing remote progress reports. */
internal class WatchNextDesktopFixture(instrumentation: Instrumentation) : AutoCloseable {
    val item = MediaItem("desktop-fixture", MediaType.MOVIE, "Lumy Watch Next Desktop Fixture",
        thumb = "/poster.png", durationMs = 600_000L, viewOffsetMs = 120_000L)
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val session = ServerSession(ServerKind.FNOS, "desktop-fixture-server", "Desktop fixture",
        "http://127.0.0.1:${socket.localPort}", "fixture-token", userId = "fixture-user", deviceId = "fixture-device")
    val requests = ConcurrentLinkedQueue<PlaybackRequest>()
    val failedReports = AtomicInteger()
    val homeReads = AtomicInteger()
    val authorizedImages = AtomicInteger()
    val publicImages = AtomicInteger()
    val publicImageUrl get() = "http://127.0.0.1:${socket.localPort}/poster.png?public=1"
    @Volatile var imageVersion = 0
    private val nextImage = AtomicReference<ImageGate?>()
    private val video = File(instrumentation.targetContext.cacheDir, "watch-next-fixture.mp4")
    private val poster = poster()
    private val imageThread = thread(isDaemon = true, name = "watch-next-poster") { serveImages() }
    private val versions = listOf(version("first", 0), version("stable-second", 1))

    init {
        instrumentation.context.assets.open("watch-next/fixture.mp4").use { input ->
            video.outputStream().use { input.copyTo(it) }
        }
    }

    val server: MediaServer = Proxy.newProxyInstance(MediaServer::class.java.classLoader,
        arrayOf(MediaServer::class.java)) { _, method, args ->
        when (method.name) {
            "getSession" -> session
            "getEmbedsSubtitles", "getSupportsFavorites" -> false
            "isReachable" -> true
            "imageUrl" -> "http://127.0.0.1:${socket.localPort}/Items/desktop-fixture/Images/Primary?v=$imageVersion"
            "playbackHeaders" -> mapOf("X-Emby-Token" to "fixture-token", "Authorization" to "fixture-token")
            "detail" -> MediaDetail(item, versions = versions)
            "homeHubs" -> {
                homeReads.incrementAndGet()
                listOf(Hub("resume", "Continue Watching", HubStyle.LANDSCAPE, listOf(item), HubLabel.ContinueWatching))
            }
            "playbackSource" -> source(args!![0] as PlaybackRequest)
            "reportProgress" -> {
                failedReports.incrementAndGet()
                throw IOException("Intentional offline progress report")
            }
            "containerItems" -> listOf(item)
            "libraries", "children", "similar", "playlists" -> emptyList<MediaItem>()
            "stopPlayback", "saveStreamSelection", "setWatched" -> Unit
            "nextEpisode", "danmakuResource" -> null
            "toString" -> "WatchNextDesktopFixture"
            "hashCode" -> System.identityHashCode(this)
            "equals" -> false
            else -> error("Unexpected fixture method: ${method.name}")
        }
    } as MediaServer

    class ImageGate {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var timedOut = false
    }

    fun holdNextImage(): ImageGate = ImageGate().also {
        check(nextImage.compareAndSet(null, it)) { "An image gate is already installed" }
    }

    private fun holdAuthorizedImage() {
        val gate = nextImage.getAndSet(null) ?: return
        gate.started.countDown()
        gate.timedOut = !gate.release.await(8, TimeUnit.SECONDS)
    }

    private fun source(request: PlaybackRequest): PlaybackSource {
        requests.add(request)
        return PlaybackSource(listOf(video.toURI().toString()), false, emptyList(), 0, request.startMs)
    }

    private fun version(id: String, index: Int) = MediaVersion(id, index, "mp4", 32,
        VideoStream("h264", 128, 72, 10f, 8, "baseline", HdrType.NONE, null), emptyList(), emptyList(),
        listOf(MediaPart("part-$id", "/fixture.mp4", "mp4", null, 600_000L)))

    private fun poster(): ByteArray {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(30, 55, 95))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 38f }
        canvas.drawText("Lumy Watch Next", 45f, 165f, paint)
        canvas.drawText("Desktop Fixture", 45f, 220f, paint)
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            bitmap.recycle()
            it.toByteArray()
        }
    }

    private fun serveImages() {
        while (!socket.isClosed) {
            try {
                socket.accept().use { client ->
                    client.soTimeout = 3_000
                    val input = client.getInputStream().bufferedReader()
                    var authorized = false
                    var line = input.readLine()
                    val publicRequest = line?.contains("public=1") == true
                    while (!line.isNullOrEmpty()) {
                        if (line.equals("X-Emby-Token: fixture-token", ignoreCase = true)) authorized = true
                        line = input.readLine()
                    }
                    if (authorized) {
                        authorizedImages.incrementAndGet()
                        holdAuthorizedImage()
                    }
                    if (publicRequest) publicImages.incrementAndGet()
                    val body = if (authorized || publicRequest) poster else byteArrayOf()
                    val status = if (authorized || publicRequest) "200 OK" else "401 Unauthorized"
                    client.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Type: image/png\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            } catch (_: IOException) { /* Closing the fixture interrupts accept. */ }
        }
    }

    override fun close() {
        nextImage.getAndSet(null)?.release?.countDown()
        socket.close()
        imageThread.join(3_500)
        video.delete()
    }
}

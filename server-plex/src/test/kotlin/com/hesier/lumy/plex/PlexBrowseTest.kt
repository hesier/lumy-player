package com.hesier.lumy.plex

import com.hesier.lumy.core.LibraryQuery
import com.hesier.lumy.core.MarkerType
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.ResolutionFilter
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import com.hesier.lumy.core.SortOrder
import com.hesier.lumy.core.WatchFilter
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexBrowseTest {
    private val client = PlexClient(PlexClientInfo("Test", "1", "id", "12", "Emu"))
    private val web = MockWebServer()

    @After
    fun tearDown() = web.shutdown()

    @Test
    fun `markers come from detail response sorted by start`() {
        val json = """{"MediaContainer":{"Metadata":[{"ratingKey":"1","type":"episode","Marker":[
            {"type":"credits","startTimeOffset":1532099,"endTimeOffset":1708352,"final":true},
            {"type":"intro","startTimeOffset":76384,"endTimeOffset":165115},
            {"type":"commercial","startTimeOffset":1,"endTimeOffset":2}]}]}}"""
        val markers = client.json.decodeFromString<Envelope>(json).container.metadata.single().toDetail(client.json).markers
        assertEquals(listOf(MarkerType.INTRO, MarkerType.CREDITS), markers.map { it.type })
        assertEquals(76384L, markers.first().startMs)
    }

    @Test
    fun `search rows are titled by library with fallbacks`() {
        assertEquals("动漫", searchRowTitle("show", "动漫"))
        assertEquals("TV Shows", searchRowTitle("show", null))
        assertEquals("综艺 · Episodes", searchRowTitle("episode", "综艺"))
        assertEquals("Movies", searchRowTitle("movie", ""))
    }

    @Test
    fun `query maps to plex params`() {
        val q = LibraryQuery(SortOrder.ADDED, WatchFilter.UNWATCHED, genreId = "63", resolution = ResolutionFilter.UHD_4K)
        assertEquals(
            listOf("sort" to "addedAt:desc", "unwatched" to "1", "genre" to "63", "resolution" to "4k"),
            q.params().toList(),
        )
        assertEquals(listOf("sort" to "titleSort"), LibraryQuery().params().toList())
    }

    @Test
    fun `multi-part playback starts in the right part with per-part sessions`() = runBlocking {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("{}")
        }
        web.start()
        val base = web.url("").toString().trimEnd('/')
        val server = PlexMediaServer(ServerSession(ServerKind.PLEX, "m", "NAS", base, "t"), client)
        val version = com.hesier.lumy.core.MediaVersion(
            id = "v", index = 0, container = "mkv", bitrateKbps = null, video = null, audio = emptyList(), subtitles = emptyList(),
            parts = listOf(
                com.hesier.lumy.core.MediaPart("p1", "/library/parts/1/file.mkv", "mkv", null, 9_684_717),
                com.hesier.lumy.core.MediaPart("p2", "/library/parts/2/file.mkv", "mkv", null, 2_202_534),
            ),
        )
        val item = MediaItem("52663", MediaType.MOVIE, "concert")
        fun req(mode: com.hesier.lumy.core.PlaybackMode, start: Long) = com.hesier.lumy.core.PlaybackRequest(item, version, mode, start, "s")

        // 进度停在第一段末尾前 1 毫秒（真实数据）：应从第二段开头继续
        val direct = server.playbackSource(req(com.hesier.lumy.core.PlaybackMode.Direct, 9_684_716))
        assertEquals(1, direct.startPart)
        assertEquals(0L, direct.startOffsetMs)
        assertEquals(listOf("$base/library/parts/1/file.mkv", "$base/library/parts/2/file.mkv"), direct.urls)
        assertEquals(0, web.requestCount)

        val hls = server.playbackSource(req(com.hesier.lumy.core.PlaybackMode.Transcode(), 60_000))
        assertEquals(0, hls.startPart)
        val first = okhttp3.HttpUrl.Companion.run { hls.urls[0].toHttpUrl() }
        val second = okhttp3.HttpUrl.Companion.run { hls.urls[1].toHttpUrl() }
        assertEquals("0", first.queryParameter("partIndex"))
        assertEquals("60", first.queryParameter("offset"))
        assertEquals("s-0", first.queryParameter("session"))
        assertEquals("1", second.queryParameter("partIndex"))
        assertEquals("0", second.queryParameter("offset"))
        assertEquals("s-1", second.queryParameter("session"))
        assertEquals(null, first.queryParameter("X-Plex-Session-Identifier"))
        // 每段起播前都先请求 decision，服务端才会按当前选择的字幕转码
        val decisions = List(2) { web.takeRequest().requestUrl!! }
        assertEquals(listOf("/video/:/transcode/universal/decision"), decisions.map { it.encodedPath }.distinct())
        assertEquals(listOf("s-0", "s-1"), decisions.map { it.queryParameter("session") })
        assert("videoCodec=h264,hevc&" in first.queryParameter("X-Plex-Client-Profile-Extra")!!)

        // 输出 SDR 时只允许 H.264
        val sdr = server.playbackSource(req(com.hesier.lumy.core.PlaybackMode.Transcode(copyVideo = false, sdr = true), 0))
        val sdrUrl = okhttp3.HttpUrl.Companion.run { sdr.urls[0].toHttpUrl() }
        assert("videoCodec=h264&" in sdrUrl.queryParameter("X-Plex-Client-Profile-Extra")!!)
        assertEquals("0", sdrUrl.queryParameter("directStream"))
    }

    @Test
    fun `next episode crosses into the next season`() = runBlocking {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.requestUrl!!.encodedPath) {
                    "/library/metadata/s1/children" -> """{"MediaContainer":{"Metadata":[{"ratingKey":"e1","type":"episode"},{"ratingKey":"e2","type":"episode"}]}}"""
                    "/library/metadata/show/children" -> """{"MediaContainer":{"Metadata":[{"ratingKey":"s1","type":"season"},{"ratingKey":"s2","type":"season"}]}}"""
                    "/library/metadata/s2/children" -> """{"MediaContainer":{"Metadata":[{"ratingKey":"e3","type":"episode"}]}}"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
        web.start()
        val server = PlexMediaServer(ServerSession(ServerKind.PLEX, "m", "NAS", web.url("/").toString().trimEnd('/'), "t"), client)
        fun ep(id: String) = MediaItem(id, MediaType.EPISODE, id, parentId = "s1", grandparentId = "show")

        assertEquals("e2", server.nextEpisode(ep("e1"))!!.id)
        assertEquals("e3", server.nextEpisode(ep("e2"))!!.id)
        assertNull(server.nextEpisode(ep("e3").copy(parentId = "s2")))
    }

    @Test
    fun `body cut off mid-read fails instead of hanging`() {
        web.enqueue(MockResponse().setBody("x".repeat(100_000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val result = runBlocking {
            withTimeout(5_000) { runCatching { client.execute(Request.Builder().url(web.url("/x")).build()) } }
        }
        assertTrue(result.isFailure)
    }
}

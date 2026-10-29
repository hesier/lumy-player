package com.hesier.lumy.jellyfin

import com.hesier.lumy.core.HdrType
import com.hesier.lumy.core.LibraryQuery
import com.hesier.lumy.core.MarkerType
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.AudioStream
import com.hesier.lumy.core.PlaybackMode
import com.hesier.lumy.core.PlaybackRequest
import com.hesier.lumy.core.PlaybackState
import com.hesier.lumy.core.ResolutionFilter
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import com.hesier.lumy.core.SortOrder
import com.hesier.lumy.core.WatchFilter
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Test

class JellyfinTest {
    private val client = JellyfinClient(JellyfinClientInfo("Lumy", "1", "dev1", "Box"))
    private val web = MockWebServer()

    @After
    fun tearDown() = web.shutdown()

    private fun server(kind: ServerKind = ServerKind.JELLYFIN) = JellyfinMediaServer(
        ServerSession(kind, "srv", "NAS", web.url("/").toString().trimEnd('/'), "tok", userId = "u1"),
        client,
    )

    @Test
    fun `address gets scheme and default port`() {
        assertEquals("http://192.168.1.5:8096", JellyfinConnector.normalizeAddress("192.168.1.5"))
        assertEquals("http://nas:9000", JellyfinConnector.normalizeAddress(" nas:9000/ "))
        assertEquals("https://media.example.com", JellyfinConnector.normalizeAddress("https://media.example.com"))
        assertEquals("https://example.com/jellyfin", JellyfinConnector.normalizeAddress("https://example.com/jellyfin/"))
        assertNull(JellyfinConnector.normalizeAddress("  "))
    }

    @Test
    fun `episode maps ids images and progress`() {
        val json = """{"Id":"e1","Name":"Pilot","Type":"Episode","SeriesId":"s1","SeriesName":"Show","SeasonId":"se1",
            "IndexNumber":2,"ParentIndexNumber":1,"RunTimeTicks":36000000000,"ImageTags":{"Primary":"p"},
            "SeriesPrimaryImageTag":"sp","ParentBackdropItemId":"s1","ParentBackdropImageTags":["b"],
            "UserData":{"PlaybackPositionTicks":6000000000,"Played":false}}"""
        val item = client.json.decodeFromString<BaseItem>(json).toItem()
        assertEquals(MediaType.EPISODE, item.type)
        assertEquals("se1", item.parentId)
        assertEquals("s1", item.grandparentId)
        assertEquals("Show", item.showTitle)
        assertEquals(1, item.seasonNumber)
        assertEquals(2, item.episodeNumber)
        assertEquals("Items/s1/Images/Primary?tag=sp", item.poster)
        assertEquals("Items/e1/Images/Primary?tag=p", item.thumb)
        assertEquals("Items/s1/Images/Backdrop/0?tag=b", item.backdrop)
        assertEquals(3_600_000L, item.durationMs)
        assertEquals(600_000L, item.viewOffsetMs)
    }

    @Test
    fun `media source maps streams with plex style codec names`() {
        val json = """{"Id":"m1","Type":"Movie","RunTimeTicks":100000000,"MediaSources":[{"Id":"src","Container":"mov,mp4,m4a",
            "Bitrate":20000000,"DefaultAudioStreamIndex":2,"DefaultSubtitleStreamIndex":4,"MediaStreams":[
            {"Type":"Video","Index":0,"Codec":"hevc","Width":3840,"Height":2160,"BitDepth":10,"VideoRangeType":"DOVIWithHDR10","DvProfile":8},
            {"Type":"Audio","Index":1,"Codec":"aac","Channels":2,"DisplayTitle":"AAC Stereo"},
            {"Type":"Audio","Index":2,"Codec":"eac3","Channels":6},
            {"Type":"Subtitle","Index":3,"Codec":"PGSSUB"},
            {"Type":"Subtitle","Index":4,"Codec":"subrip","IsExternal":true,"Language":"chi"}]}],
            "Chapters":[{"StartPositionTicks":0,"Name":"Start","ImageTag":"c0"},{"StartPositionTicks":50000000,"MarkerType":"IntroStart"},
            {"StartPositionTicks":60000000,"MarkerType":"IntroEnd"},{"StartPositionTicks":70000000,"Name":"Two"}]}"""
        val detail = client.json.decodeFromString<BaseItem>(json).toDetail(emptyList(), null)
        val v = detail.versions.single()
        assertEquals("mov", v.container)
        assertEquals(20_000, v.bitrateKbps)
        assertEquals(HdrType.DOLBY_VISION, v.video?.hdr)
        assertEquals(8, v.video?.doviProfile)
        assertEquals(listOf("1", "2"), v.audio.map { it.id })
        assertTrue(v.audio[1].selected)
        assertEquals("pgs", v.subtitles[0].codec)
        assertNull(v.subtitles[0].key)
        assertEquals("srt", v.subtitles[1].codec)
        assertTrue(v.subtitles[1].selected)
        assertEquals("Videos/m1/src/Subtitles/4/Stream.srt", v.subtitles[1].key)
        // 播放器按容器流序号对应轨道；外挂字幕没有序号
        assertEquals(listOf(1, 2), v.audio.map { it.index })
        assertEquals(listOf(3, null), v.subtitles.map { it.index })
        // Emby 的片头标记不算章节
        assertEquals(listOf(0L, 7_000L), detail.chapters.map { it.startMs })
        assertEquals(7_000L, detail.chapters[0].endMs)
        assertEquals("Items/m1/Images/Chapter/0?tag=c0", detail.chapters[0].thumb)
        assertEquals(listOf(MarkerType.INTRO), detail.markers.map { it.type })
        assertEquals(5_000L, detail.markers[0].startMs)
    }

    @Test
    fun `emby numeric studio ids do not break detail parsing`() {
        val json = """{"Id":"388","Type":"Movie","Studios":[{"Name":"ufotable","Id":649}],"GenreItems":[{"Name":"动画","Id":699}]}"""
        assertEquals("ufotable", client.json.decodeFromString<BaseItem>(json).toDetail(emptyList(), null).studio)
    }

    @Test
    fun `query maps to item params`() {
        val q = LibraryQuery(SortOrder.ADDED, WatchFilter.UNWATCHED, genreId = "g", resolution = ResolutionFilter.FHD_1080)
        assertEquals(
            listOf(
                "SortBy" to "DateCreated,SortName", "SortOrder" to "Descending", "Filters" to "IsUnplayed",
                "GenreIds" to "g", "MinWidth" to "1900", "MaxWidth" to "3799",
            ),
            q.params().toList(),
        )
    }

    @Test
    fun `transcode url carries selection and limits`() = runBlocking {
        val detail = client.json.decodeFromString<BaseItem>(
            """{"Id":"m1","Type":"Movie","MediaSources":[{"Id":"src","MediaStreams":[]}]}""",
        ).toDetail(emptyList(), null)
        val req = PlaybackRequest(
            detail.item, detail.versions.single(), PlaybackMode.Transcode(maxBitrateKbps = 4000, maxHeight = 720, burnSubtitleId = "5", copyVideo = false),
            startMs = 1000, sessionId = "ps", audioStreamId = "2",
        )
        val url = server().playbackSource(req).urls.single().toHttpUrl()
        assertEquals("/Videos/m1/master.m3u8", url.encodedPath)
        assertEquals("src", url.queryParameter("MediaSourceId"))
        assertEquals("2", url.queryParameter("AudioStreamIndex"))
        assertEquals("5", url.queryParameter("SubtitleStreamIndex"))
        assertEquals("4000000", url.queryParameter("MaxStreamingBitrate"))
        assertEquals("false", url.queryParameter("AllowVideoStreamCopy"))
        assertEquals("tok", url.queryParameter("ApiKey"))

        // 不限画质也要带码率，否则服务端按 640 kbps 转码
        val reencode = server().playbackSource(req.copy(mode = PlaybackMode.Transcode(maxHeight = 1080, copyVideo = false))).urls.single().toHttpUrl()
        assertEquals("20000000", reencode.queryParameter("VideoBitrate"))
        assertEquals("false", reencode.queryParameter("AllowVideoStreamCopy"))
        assertEquals("h264,hevc", reencode.queryParameter("VideoCodec"))
        val sdr = server().playbackSource(req.copy(mode = PlaybackMode.Transcode(maxHeight = 1080, copyVideo = false, sdr = true))).urls.single().toHttpUrl()
        assertEquals("h264", sdr.queryParameter("VideoCodec"))
        val copy = server().playbackSource(req.copy(mode = PlaybackMode.Transcode())).urls.single().toHttpUrl()
        assertEquals("200000000", copy.queryParameter("MaxStreamingBitrate"))
        assertNull(copy.queryParameter("VideoBitrate"))

        val direct = server(ServerKind.EMBY).playbackSource(req.copy(mode = PlaybackMode.Direct))
        val d = direct.urls.single().toHttpUrl()
        assertEquals("/Videos/m1/stream", d.encodedPath)
        assertEquals("tok", d.queryParameter("api_key"))
        assertEquals(1000L, direct.startOffsetMs)

        // 带内封字幕时改用 MKV 渐进式转码，从起播点开始，字幕原样复制
        val embed = server().playbackSource(req.copy(mode = PlaybackMode.Transcode(embedSubtitleId = "3")))
        val e = embed.urls.single().toHttpUrl()
        assertEquals(false, embed.isHls)
        assertEquals("/Videos/m1/stream.mkv", e.encodedPath)
        assertEquals("3", e.queryParameter("SubtitleStreamIndex"))
        assertEquals("Embed", e.queryParameter("SubtitleMethod"))
        assertEquals("copy", e.queryParameter("SubtitleCodec"))
        assertEquals("10000000", e.queryParameter("StartTimeTicks"))
        assertNull(e.queryParameter("SegmentContainer"))
        assertEquals("aac,ac3,eac3,mp3,opus,flac", e.queryParameter("AudioCodec"))

        // MKV 能带 TrueHD：本机能解码时把原音轨格式也列上，服务端才会原样复制；HLS 不加
        val truehd = req.copy(
            version = req.version.copy(audio = listOf(AudioStream("2", 1, "truehd", 8, "en", "English", true))),
        )
        val copied = server().playbackSource(truehd.copy(mode = PlaybackMode.Transcode(embedSubtitleId = "3", copyAudio = true))).urls.single().toHttpUrl()
        assertEquals("aac,ac3,eac3,mp3,opus,flac,truehd", copied.queryParameter("AudioCodec"))
        val hlsAudio = server().playbackSource(truehd.copy(mode = PlaybackMode.Transcode(copyAudio = true))).urls.single().toHttpUrl()
        assertEquals("aac,ac3,eac3,mp3,opus,flac", hlsAudio.queryParameter("AudioCodec"))
    }

    @Test
    fun `progress reports start then progress then stopped`() = runBlocking {
        repeat(3) { web.enqueue(MockResponse().setResponseCode(204)) }
        val detail = client.json.decodeFromString<BaseItem>(
            """{"Id":"m1","Type":"Movie","MediaSources":[{"Id":"src","MediaStreams":[]}]}""",
        ).toDetail(emptyList(), null)
        val req = PlaybackRequest(detail.item, detail.versions.single(), PlaybackMode.Direct, 0, "ps")
        val s = server()
        s.reportProgress(req, PlaybackState.PLAYING, 1000, 5000)
        s.reportProgress(req, PlaybackState.PAUSED, 2000, 5000)
        s.reportProgress(req, PlaybackState.STOPPED, 3000, 5000)
        val paths = (1..3).map { web.takeRequest() }
        assertEquals(listOf("/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped"), paths.map { it.path })
        val body = paths[1].body.readUtf8()
        assertTrue(body.contains("\"PositionTicks\":20000000"))
        assertTrue(body.contains("\"IsPaused\":true"))
        assertTrue(body.contains("\"SubtitleStreamIndex\":-1"))
        assertTrue(paths[0].getHeader("Authorization")!!.contains("Token=\"tok\""))
    }

    @Test
    fun `libraries fall back to legacy route and keep video views`() = runBlocking {
        web.enqueue(MockResponse().setResponseCode(404))
        web.enqueue(
            MockResponse().setBody(
                """{"Items":[{"Id":"a","Name":"电影","CollectionType":"movies"},{"Id":"b","Name":"音乐","CollectionType":"music"},
                {"Id":"c","Name":"剧集","CollectionType":"tvshows"}]}""",
            ),
        )
        val libs = server().libraries()
        assertEquals(listOf("a", "c"), libs.map { it.id })
        assertEquals(listOf(MediaType.MOVIE, MediaType.SHOW), libs.map { it.type })
        assertEquals("/UserViews?userId=u1", web.takeRequest().path)
        assertEquals("/Users/u1/Views", web.takeRequest().path)
    }

    @Test
    fun `each sign-in gets its own device id and requests use it`() = runBlocking {
        val auth = """{"AccessToken":"t","User":{"Id":"u1"},"ServerId":"srv"}"""
        repeat(2) { web.enqueue(MockResponse().setBody(auth)) }
        val connector = JellyfinConnector(client)
        val target = JellyfinConnector.Server(web.url("/").toString().trimEnd('/'), ServerKind.JELLYFIN, PublicSystemInfo("srv"))
        val a = connector.signIn(target, "hesier", "pw")
        val b = connector.signIn(target, "hesier", "pw")
        assertTrue(a.deviceId != b.deviceId)
        assertTrue(web.takeRequest().getHeader("Authorization")!!.contains("DeviceId=\"${a.deviceId}\""))
        assertTrue(web.takeRequest().getHeader("Authorization")!!.contains("DeviceId=\"${b.deviceId}\""))
        // 之后的请求和播放地址都要用登录时的标识，否则 token 对不上
        val server = JellyfinMediaServer(a, client)
        assertTrue(server.playbackHeaders()["Authorization"]!!.contains("DeviceId=\"${a.deviceId}\""))
    }

    @Test
    fun `emby uses its own auth header`() {
        val h = client.headers(ServerKind.EMBY, "tok")
        assertEquals("tok", h["X-Emby-Token"])
        assertTrue(h["X-Emby-Authorization"]!!.startsWith("MediaBrowser Client=\"Lumy\""))
        assertFalse(h.containsKey("Authorization"))
    }

    @Test
    fun `body cut off mid-read fails instead of hanging`() {
        web.enqueue(MockResponse().setBody("x".repeat(100_000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val result = runBlocking {
            withTimeout(5_000) { runCatching { client.execute(Request.Builder().url(web.url("/x")).build()) } }
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun `collections only include box sets with items from the library`() = runBlocking {
        web.enqueue(MockResponse().setBody("""{"Items":[{"Id":"m1","Type":"Movie"},{"Id":"b1","Type":"BoxSet"},{"Id":"s1","Type":"Series"}]}"""))
        web.enqueue(MockResponse().setBody("""{"Items":[{"Id":"b1","Name":"Saga","Type":"BoxSet"}]}"""))
        val result = server().collections("lib1")
        assertEquals(listOf("b1"), result.map { it.id })
        val first = web.takeRequest().requestUrl!!
        assertEquals("lib1", first.queryParameter("ParentId"))
        assertEquals("true", first.queryParameter("CollapseBoxSetItems"))
        assertEquals("b1", web.takeRequest().requestUrl!!.queryParameter("Ids"))
    }

    @Test
    fun `library without box sets has no collections`() = runBlocking {
        web.enqueue(MockResponse().setBody("""{"Items":[{"Id":"m1","Type":"Movie"}]}"""))
        assertTrue(server().collections("lib1").isEmpty())
        assertEquals(1, web.requestCount)
    }
}

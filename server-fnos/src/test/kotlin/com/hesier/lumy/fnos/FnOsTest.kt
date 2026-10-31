package com.hesier.lumy.fnos

import com.hesier.lumy.core.HdrType
import com.hesier.lumy.core.LibraryQuery
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import com.hesier.lumy.core.WatchFilter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FnOsTest {
    private val client = FnOsClient(FnOsClientInfo("Test Player", "1", "test-device", "Test Box"))
    private val native = FnOsTestDispatcher()
    private val web = MockWebServer().apply { dispatcher = native }

    private fun takeCompatRequest(): okhttp3.mockwebserver.RecordedRequest {
        while (true) {
            val request = web.takeRequest()
            if (!request.requestUrl!!.encodedPath.startsWith("/v/")) return request
        }
    }

    @After
    fun tearDown() = web.shutdown()

    private fun server() = FnOsMediaServer(
        ServerSession(
            ServerKind.FNOS, "test-server", "Test Media", web.url("/").toString().trimEnd('/'),
            "test-token", userId = "test-user",
        ),
        client,
    )

    private fun enqueue(body: String) = web.enqueue(MockResponse().setBody(body))

    @Test
    fun `fnOS identification takes precedence over Jellyfin product and uses Emby login auth`() = runBlocking {
        enqueue("""{"Id":"test-server","ServerName":"Test Media","OperatingSystem":"fnOS","ProductName":"Jellyfin"}""")
        enqueue("""{"AccessToken":"test-token","User":{"Id":"test-user"},"ServerId":"test-server"}""")
        val connector = FnOsConnector(client)
        val found = connector.probe(web.url("/").toString())
        assertEquals(ServerKind.FNOS, found.kind)
        val session = connector.signIn(found, "test-account", "test-password")
        assertEquals(ServerKind.FNOS, session.kind)
        assertEquals("/System/Info/Public", takeCompatRequest().requestUrl!!.encodedPath)
        val login = takeCompatRequest()
        assertEquals("/Users/AuthenticateByName", login.requestUrl!!.encodedPath)
        assertTrue(login.getHeader("X-Emby-Authorization")!!.contains("DeviceId=\"${session.deviceId}\""))
        assertNull(login.getHeader("Authorization"))
        assertTrue(login.body.readUtf8().contains("test-account"))
    }

    @Test
    fun `dedicated fnOS connector keeps fnOS kind when compatibility product says Jellyfin`() = runBlocking {
        enqueue("""{"Id":"test-server","ServerName":"Test Media","ProductName":"Jellyfin"}""")
        val found = FnOsConnector(client).probe(web.url("/").toString())
        assertEquals(ServerKind.FNOS, found.kind)
        assertEquals("/System/Info/Public", takeCompatRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `fnOS default port keeps explicit ports HTTPS and reverse proxy paths`() {
        assertEquals("http://media.test:8005", FnOsConnector.normalizeAddress("media.test"))
        assertEquals("http://media.test:9000/media", FnOsConnector.normalizeAddress("media.test:9000/media/"))
        assertEquals("https://media.test/media", FnOsConnector.normalizeAddress("https://media.test/media/"))
        assertEquals("https://media.test:9443", FnOsConnector.normalizeAddress("https://media.test:9443"))
    }

    @Test
    fun `reachability checks server identity through anonymous public info`() = runBlocking {
        val media = server()
        for ((serverId, expected) in listOf("test-server" to true, "other-server" to false)) {
            enqueue("""{"Id":"$serverId","ServerName":"Test Media"}""")
            assertEquals(expected, media.isReachable())
            val request = takeCompatRequest()
            assertEquals("/System/Info/Public", request.path)
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("X-Emby-Token"))
            assertFalse(request.getHeader("X-Emby-Authorization").orEmpty().contains("Token="))
        }
        assertEquals(2, web.requestCount)
    }

    @Test
    fun `views without collection types infer each library from its first item`() = runBlocking {
        enqueue("""{"Items":[{"Id":"movies","Name":"Films"},{"Id":"shows","Name":"Series"}]}""")
        enqueue("""{"Items":[{"Id":"film-a","Type":"Movie"}]}""")
        enqueue("""{"Items":[{"Id":"show-a","Type":"Series"}]}""")
        val libraries = server().libraries()
        assertEquals(listOf("movies", "shows"), libraries.map { it.id })
        assertEquals(listOf(MediaType.MOVIE, MediaType.SHOW), libraries.map { it.type })
        assertEquals("/UserViews", takeCompatRequest().requestUrl!!.encodedPath)
        for (library in listOf("movies", "shows")) {
            val url = takeCompatRequest().requestUrl!!
            assertEquals("/Items", url.encodedPath)
            assertEquals(library, url.queryParameter("parentId"))
            assertEquals("1", url.queryParameter("limit"))
            assertNull(url.queryParameter("ParentId"))
        }
    }

    @Test
    fun `native queries select matches and compatibility catalog preserves file IDs`() = runBlocking {
        enqueue("""{"Items":[{"Id":"film-a","Name":"Other Film","Type":"Movie","UserData":{"Played":true}}],"TotalRecordCount":2}""")
        enqueue("""{"Items":[{"Id":"film-b","Name":"Needle Film","Type":"Movie","UserData":{"Played":false}}],"TotalRecordCount":2}""")
        native.items = """{"list":[{"guid":"film-b","watched":0}],"total":1}"""
        native.search = """[{"guid":"film-b","ancestor_guid":"movies"}]"""
        val media = server()
        val page = media.libraryItems("movies", 0, 10, LibraryQuery(watch = WatchFilter.UNWATCHED))
        assertEquals(listOf("film-b"), page.items.map { it.id })
        assertEquals(1, page.total)
        for (offset in listOf("0", "1")) {
            val url = takeCompatRequest().requestUrl!!
            assertEquals("/Items", url.encodedPath)
            assertEquals("movies", url.queryParameter("parentId"))
            assertEquals(offset, url.queryParameter("startIndex"))
            assertEquals("200", url.queryParameter("limit"))
            assertNull(url.queryParameter("StartIndex"))
            assertNull(url.queryParameter("Limit"))
        }
        enqueue("""{"Items":[{"Id":"movies","Name":"Films"}]}""")
        enqueue("""{"Items":[{"Id":"film-a","Type":"Movie"}]}""")
        val results = media.search("needle")
        assertEquals(listOf("film-b"), results.single().items.map { it.id })
        assertEquals(4, native.compatCount.get())
        val request = native.requests.single { it.requestUrl!!.encodedPath.endsWith("/item/list") }
        val parameters = client.json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("0", parameters["tags"]!!.jsonObject["watched"]!!.jsonPrimitive.content)
        assertEquals("10", parameters["page_size"]!!.jsonPrimitive.content)
        assertEquals("needle", native.requests.single {
            it.requestUrl!!.encodedPath.endsWith("/search/list")
        }.requestUrl!!.queryParameter("q"))
    }

    @Test
    fun `detail uses source duration unique track ids defaults and subtitle ordinal routes`() = runBlocking {
        enqueue(
            """{"Id":"film-a","Name":"Test Film","Type":"Movie","RunTimeTicks":1000000000,
            "BackdropImageTags":["test-image"],"MediaSources":[{"Id":"source-a","RunTimeTicks":1200000000,
            "DefaultAudioStreamIndex":0,"DefaultSubtitleStreamIndex":0,"MediaStreams":[
            {"Type":"Video","Index":0,"Codec":"h264"},
            {"Type":"Audio","Index":0,"Codec":"aac","IsDefault":false,"DisplayTitle":"  "},
            {"Type":"Audio","Index":0,"Codec":"ac3","IsDefault":true},
            {"Type":"Subtitle","Index":0,"Codec":"hdmv_pgs_subtitle","IsDefault":false},
            {"Type":"Subtitle","Index":0,"Codec":"subrip","IsExternal":true,"IsDefault":true}]}]}""",
        )
        val media = server()
        val detail = media.detail("film-a")
        assertEquals("/Items/film-a", takeCompatRequest().requestUrl!!.encodedPath)
        assertEquals(120_000L, detail.item.durationMs)
        val version = detail.versions.single()
        assertEquals(120_000L, version.part.durationMs)
        assertEquals(listOf("audio:0", "audio:1"), version.audio.map { it.id })
        assertEquals(listOf(null, null), version.audio.map { it.index })
        assertEquals(listOf(false, true), version.audio.map { it.selected })
        assertEquals("AAC", version.audio.first().title)
        assertEquals(listOf("subtitle:0", "subtitle:1"), version.subtitles.map { it.id })
        assertEquals(listOf(null, null), version.subtitles.map { it.index })
        assertEquals(listOf(false, true), version.subtitles.map { it.selected })
        assertEquals("pgs", version.subtitles.first().codec)
        assertNull(version.subtitles.first().key)
        assertEquals("Videos/film-a/source-a/Subtitles/1/0/Stream.srt", version.subtitles.last().key)
        assertFalse(media.embedsSubtitles)
        val backdrop = media.imageUrl(detail.item.backdrop!!, 1280, 720)
        assertTrue(backdrop.contains("/Items/film-a/Images/Backdrop?"))
        assertFalse(backdrop.contains("/Backdrop/0"))
    }

    @Test
    fun `detail restores native duration GUIDs indices and remembered stream selection`() = runBlocking {
        enqueue("""{"Id":"file-a","Type":"Movie","RunTimeTicks":10000000,
            "MediaSources":[{"Id":"canonical-a","RunTimeTicks":10000000,"MediaStreams":[
            {"Type":"Video","Codec":"h264"},{"Type":"Audio","Codec":"aac","Index":0}]}]}""")
        native.info = """{"guid":"canonical-a","media_guid":"file-a",
            "audio_guid":"audio-b","subtitle_guid":"","item":{"duration":240}}"""
        native.streams = """{"video_stream":{"guid":"video-a","media_guid":"file-a","index":0,
            "codec_name":"hevc","width":3840,"height":2160,"duration":240,"wrapper":"mkv",
            "dv_profile":8,"color_range_type":"HDR10","bit_depth":10},"audio_streams":[
            {"guid":"audio-a","index":2,"codec_name":"eac3","is_default":1},
            {"guid":"audio-b","index":5,"codec_name":"aac","is_default":0}],"subtitle_streams":[
            {"guid":"subtitle-a","index":7,"codec_name":"hdmv_pgs_subtitle","is_default":1},
            {"guid":"subtitle-b","index":8,"codec_name":"subrip","format":"srt","is_external":1}]}"""
        val detail = server().detail("file-a")
        assertEquals(240_000L, detail.item.durationMs)
        val version = detail.versions.single()
        assertEquals("canonical-a", version.id)
        assertEquals("Videos/file-a/stream", version.part.key)
        assertEquals(240_000L, version.part.durationMs)
        assertEquals("hevc", version.video!!.codec)
        assertEquals(HdrType.DOLBY_VISION, version.video!!.hdr)
        assertEquals(listOf("audio-a", "audio-b"), version.audio.map { it.id })
        assertEquals(listOf(2, 5), version.audio.map { it.index })
        assertEquals(listOf(false, true), version.audio.map { it.selected })
        assertEquals(listOf("subtitle-a", "subtitle-b"), version.subtitles.map { it.id })
        assertEquals(listOf(7, null), version.subtitles.map { it.index })
        assertTrue(version.subtitles.none { it.selected })
        assertEquals("pgs", version.subtitles.first().codec)
        assertEquals("Videos/file-a/canonical-a/Subtitles/1/0/Stream.srt", version.subtitles.last().key)
        val streamRequest = native.requests.single { it.requestUrl!!.encodedPath.endsWith("/stream") }
        val streamBody = client.json.parseToJsonElement(streamRequest.body.readUtf8()).jsonObject
        assertEquals("file-a", streamBody["media_guid"]!!.jsonPrimitive.content)
        assertEquals("1", streamBody["level"]!!.jsonPrimitive.content)
        val infoRequest = native.requests.single { it.requestUrl!!.encodedPath.endsWith("/play/info") }
        assertEquals("canonical-a", client.json.parseToJsonElement(infoRequest.body.readUtf8())
            .jsonObject["item_guid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `native mark watched uses canonical ID and invalidates catalog state`() = runBlocking {
        val item = """{"Id":"file-a","Type":"Movie","MediaSources":[{"Id":"canonical-a"}]}"""
        enqueue("""{"Items":[$item],"TotalRecordCount":1}""")
        native.items = """{"list":[{"guid":"canonical-a","watched":0}],"total":1}"""
        val media = server()
        assertEquals(1, media.libraryItems("movies", 0, 10, LibraryQuery(watch = WatchFilter.UNWATCHED)).total)
        enqueue(item)
        media.setWatched("file-a", true)
        val mark = native.requests.single { it.requestUrl!!.encodedPath.endsWith("/item/watched") }
        assertEquals("POST", mark.method)
        assertEquals("canonical-a", client.json.parseToJsonElement(mark.body.readUtf8())
            .jsonObject["item_guid"]!!.jsonPrimitive.content)
        native.items = """{"list":[],"total":0}"""
        enqueue("""{"Items":[$item],"TotalRecordCount":1}""")
        assertEquals(0, media.libraryItems("movies", 0, 10, LibraryQuery(watch = WatchFilter.UNWATCHED)).total)
        assertEquals(3, native.compatCount.get())
        assertEquals(2, native.requests.count { it.requestUrl!!.encodedPath.endsWith("/item/list") })
    }

    @Test
    fun `season children reuse the season listing because direct season detail returns null`() = runBlocking {
        enqueue("""{"Id":"show-a","Type":"Series"}""")
        enqueue("""{"Items":[{"Id":"season-a","Type":"Season","SeriesId":"show-a","IndexNumber":1}]}""")
        enqueue("""{"Items":[{"Id":"episode-a","Type":"Episode","SeriesId":"show-a","SeasonId":"season-a"}]}""")
        val media = server()
        val season = media.children("show-a").single()
        assertEquals("episode-a", media.children(season.id).single().id)
        assertEquals("/Items/show-a", takeCompatRequest().requestUrl!!.encodedPath)
        assertEquals("/Shows/show-a/Seasons", takeCompatRequest().requestUrl!!.encodedPath)
        assertEquals("/Shows/show-a/Episodes", takeCompatRequest().requestUrl!!.encodedPath)
        assertEquals(3, native.compatCount.get())
    }

    @Test
    fun `next episode scopes episode listing to the current season`() = runBlocking {
        enqueue("""{"Items":[{"Id":"season-a","Type":"Season","IndexNumber":1}]}""")
        enqueue(
            """{"Items":[
            {"Id":"episode-b","Type":"Episode","SeriesId":"show-a","SeasonId":"season-a","IndexNumber":2},
            {"Id":"episode-a","Type":"Episode","SeriesId":"show-a","SeasonId":"season-a","IndexNumber":1}]}""",
        )
        val episode = client.json.decodeFromString<BaseItem>(
            """{"Id":"episode-a","Type":"Episode","SeriesId":"show-a","SeasonId":"season-a","IndexNumber":1}""",
        ).toItem()
        assertEquals("episode-b", server().nextEpisode(episode)?.id)
        assertEquals("/Shows/show-a/Seasons", takeCompatRequest().requestUrl!!.encodedPath)
        val url = takeCompatRequest().requestUrl!!
        assertEquals("/Shows/show-a/Episodes", url.encodedPath)
        assertEquals("season-a", url.queryParameter("seasonId"))
        assertNull(url.queryParameter("SeasonId"))
    }
}

package com.hesier.lumy.fnos

import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.PlaybackMode
import com.hesier.lumy.core.PlaybackRequest
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import com.hesier.lumy.core.SubtitleStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FnOsPlaybackTest {
    private val web = MockWebServer()
    private val client = FnOsClient(FnOsClientInfo("Test", "1", "device", "Test"))

    @After
    fun close() = web.shutdown()

    private fun playback(): FnOsPlayback {
        val session = ServerSession(ServerKind.FNOS, "server", "Test", web.url("/").toString(), "test-token", "user")
        return FnOsPlayback(session, client, FnOsNativeClient(session, client))
    }

    private fun request(mode: PlaybackMode = PlaybackMode.Transcode(maxHeight = 720, maxBitrateKbps = 2000)) =
        PlaybackRequest(
            MediaItem("file-id", MediaType.MOVIE, "Movie"),
            MediaVersion("item-id", 0, null, null, null, emptyList(), emptyList(), emptyList()),
            mode, 60999, "play-session", audioStreamId = "audio:1", subtitleStreamId = "subtitle:0",
        )

    private fun bootstrap() {
        web.enqueue(MockResponse().setBody("""<script src="/v/sign.js"></script>"""))
        web.enqueue(MockResponse().setBody(FIXTURE))
    }

    private fun data(body: String) = web.enqueue(MockResponse().setBody("""{"code":0,"data":$body}"""))
    private fun RecordedRequest.bodyJson(): JsonObject = client.json.parseToJsonElement(body.readUtf8()).jsonObject

    @Test
    fun `transcode selects bounded quality and selected audio without burning UI subtitle`() = runBlocking {
        val playback = playback()
        bootstrap()
        data(STREAM)
        data("""{"play_link":"/v/media/session/preset.m3u8"}""")
        val source = playback.source(request())
        assertEquals(listOf(web.url("/v/media/session/preset.m3u8").toString()), source.urls)
        assertTrue(source.isHls)
        assertEquals(60999L, source.startOffsetMs)
        repeat(2) { web.takeRequest() }
        assertEquals("/v/api/v1/stream", web.takeRequest().path)
        val body = web.takeRequest().bodyJson()
        assertEquals("\"audio-b\"", body["audio_guid"].toString())
        assertEquals("\"_no_display_\"", body["subtitle_guid"].toString())
        assertEquals("\"720\"", body["resolution"].toString())
        assertEquals("2000000", body["bitrate"].toString())
        assertEquals("60", body["startTimestamp"].toString())
        assertEquals("\"h264\"", body["video_encoder"].toString())
        assertFalse(body.containsKey("forced_sdr"))
    }

    @Test
    fun `burn uses native subtitle and stopping retries only its own session`() = runBlocking {
        val playback = playback()
        bootstrap()
        data(STREAM)
        data("""{"play_link":"/v/media/session/preset.m3u8"}""")
        val request = request(PlaybackMode.Transcode(burnSubtitleId = "subtitle:0"))
        assertTrue(playback.source(request).externalSubtitles.isEmpty())
        repeat(3) { web.takeRequest() }
        assertEquals("\"subtitle-a\"", web.takeRequest().bodyJson()["subtitle_guid"].toString())
        data("""{"result":"fail","errno":1}""")
        data("""{"result":"succ"}""")
        playback.stop(request.copy(sessionId = "other-session"))
        playback.stop(request)
        repeat(2) {
            val body = web.takeRequest().bodyJson()
            assertEquals("\"media.quit\"", body["req"].toString())
            assertEquals("\"/v/media/session/preset.m3u8\"", body["playLink"].toString())
        }
        playback.stop(request)
        assertEquals(6, web.requestCount)
    }

    @Test
    fun `foreign link and conversion warnings clean up created sessions`() = runBlocking {
        for (response in listOf(
            """{"play_link":"http://foreign.invalid/video"}""",
            """{"play_link":"/v/media/session/preset.m3u8","non_fatal_errno":[68157454]}""",
            """{"play_link":"/v/media/session/preset.m3u8","non_fatal_errno":68157454}""",
        )) {
            val playback = playback()
            bootstrap()
            data(STREAM)
            data(response)
            data("""{"result":"succ"}""")
            try { playback.source(request()); fail("Expected rejected playback") } catch (_: IOException) { }
            repeat(4) { web.takeRequest() }
            assertEquals("\"media.quit\"", web.takeRequest().bodyJson()["req"].toString())
        }
    }

    @Test
    fun `cancellation during create waits for returned cleanup handle and quits`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/v/" -> MockResponse().setBody("""<script src="/v/sign.js"></script>""")
                "/v/sign.js" -> MockResponse().setBody(FIXTURE)
                "/v/api/v1/stream" -> MockResponse().setBody("""{"code":0,"data":$STREAM}""")
                "/v/api/v1/play/play" -> {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    MockResponse().setBody("""{"code":0,"data":{"play_link":"/v/media/session/preset.m3u8"}}""")
                }
                else -> MockResponse().setBody("""{"code":0,"data":{"result":"succ"}}""")
            }
        }
        val playback = playback()
        val job = async(Dispatchers.Default) { playback.source(request()) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        job.cancel()
        release.countDown()
        job.join()
        repeat(4) { web.takeRequest() }
        assertEquals("\"media.quit\"", web.takeRequest().bodyJson()["req"].toString())
        playback.stop(request())
        assertEquals(5, web.requestCount)
    }

    @Test
    fun `selected embedded text reaches HLS while native audio GUID is preserved`() = runBlocking {
        val playback = playback()
        bootstrap()
        data(STREAM)
        data("""{"play_link":"/v/media/session/preset.m3u8"}""")
        val original = request()
        val text = SubtitleStream("subtitle-a", 3, "srt", "eng", "English", false, true, null)
        val request = original.copy(
            version = original.version.copy(subtitles = listOf(text)),
            subtitleStreamId = text.id, audioStreamId = "audio-a",
        )
        playback.source(request)
        repeat(3) { web.takeRequest() }
        val body = web.takeRequest().bodyJson()
        assertEquals("\"subtitle-a\"", body["subtitle_guid"].toString())
        assertEquals("\"audio-a\"", body["audio_guid"].toString())
    }

    @Test
    fun `compatible audio keeps its codec only when client can decode the original`() = runBlocking {
        for (codec in listOf("aac", "ac3", "eac3", "truehd", "dts")) {
            for (copy in listOf(false, true)) {
                val playback = playback()
                bootstrap()
                data(STREAM.replace("\"guid\":\"audio-b\"", "\"guid\":\"audio-b\",\"codec_name\":\"$codec\",\"channels\":6"))
                data("""{"play_link":"/v/media/session/preset.m3u8"}""")
                playback.source(request(PlaybackMode.Transcode(copyAudio = copy)))
                repeat(3) { web.takeRequest() }
                val body = web.takeRequest().bodyJson()
                val expected = if (copy && codec in setOf("aac", "ac3", "eac3")) codec else "aac"
                assertEquals("\"$expected\"", body["audio_encoder"].toString())
                assertEquals("6", body["channels"].toString())
                assertEquals("\"audio-b\"", body["audio_guid"].toString())
                assertEquals("\"h264\"", body["video_encoder"].toString())
            }
        }
    }

    @Test
    fun `audio channel targets stay within verified stereo and surround support`() = runBlocking {
        for (channels in listOf(null, -1, 0, 1, 2, 4, 6, 8, 12)) {
            val playback = playback()
            bootstrap()
            val field = channels?.let { ",\"channels\":$it" }.orEmpty()
            data(STREAM.replace("\"guid\":\"audio-b\"", "\"guid\":\"audio-b\"$field"))
            data("""{"play_link":"/v/media/session/preset.m3u8"}""")
            playback.source(request(PlaybackMode.Transcode(copyAudio = true)))
            repeat(3) { web.takeRequest() }
            val body = web.takeRequest().bodyJson()
            assertEquals(if ((channels ?: 0) >= 6) "6" else "2", body["channels"].toString())
            assertEquals("\"aac\"", body["audio_encoder"].toString())
        }
    }

    @Test
    fun `direct playback preserves authentication resume and ordinal subtitles without native requests`() = runBlocking {
        val detail = client.json.decodeFromString<BaseItem>(
            """{"Id":"file-id","Type":"Movie","MediaSources":[{"Id":"item-id","MediaStreams":[
                {"Type":"Subtitle","Index":7,"Codec":"ass"},
                {"Type":"Subtitle","Index":23,"Codec":"subrip","IsExternal":true,"Language":"eng"}]}]}""",
        ).fnOsDetail()
        for (deviceId in listOf(null, "login-device")) {
            val session = ServerSession(
                ServerKind.FNOS, "server", "Test", web.url("/media").toString(), "test+token&value",
                userId = "user", deviceId = deviceId,
            )
            val playback = FnOsPlayback(session, client, FnOsNativeClient(session, client))
            val source = playback.source(request(PlaybackMode.Direct).copy(version = detail.versions.single()))
            val url = source.urls.single().toHttpUrl()
            assertEquals("/media/Videos/file-id/stream", url.encodedPath)
            assertEquals("test+token&value", url.queryParameter("ApiKey"))
            assertEquals("true", url.queryParameter("Static"))
            assertEquals("item-id", url.queryParameter("MediaSourceId"))
            assertEquals("play-session", url.queryParameter("PlaySessionId"))
            assertEquals(deviceId ?: "device", url.queryParameter("DeviceId"))
            assertFalse(source.isHls)
            assertEquals(0, source.startPart)
            assertEquals(60999L, source.startOffsetMs)
            val subtitle = source.externalSubtitles.single()
            assertEquals("subtitle:1", subtitle.streamId)
            assertEquals("srt", subtitle.codec)
            assertEquals("eng", subtitle.language)
            val subtitleUrl = subtitle.url.toHttpUrl()
            assertEquals("/media/Videos/file-id/item-id/Subtitles/1/0/Stream.srt", subtitleUrl.encodedPath)
            assertEquals("test+token&value", subtitleUrl.queryParameter("ApiKey"))
        }
        assertEquals(0, web.requestCount)
    }

    companion object {
        private const val STREAM = """{"video_stream":{"guid":"video"},"audio_streams":[{"guid":"audio-a","index":1,"is_default":1},{"guid":"audio-b","index":2}],"subtitle_streams":[{"guid":"subtitle-a","index":3}],"qualities":[{"resolution":"1080","bitrate":5000000},{"resolution":"720","bitrate":3000000},{"resolution":"720","bitrate":2000000},{"resolution":"480","bitrate":1000000}]}"""
        private val FIXTURE = """
            var demo=(()=>{let e=new Uint8Array([103,104,121,117,116,115,100,44,106,100,120]),t=new Uint8Array(11);for(let n=0;n<11;n++)t[n]=e[n]^1;return new TextDecoder(`utf-8`).decode(t)})();
            var sign=(r,s,c,o,t)=>{let l=[`fixture-app`,r,s,c,o,t].join(`_`);return `nonce=signature`;};
            client({apiKey:demo,header:`authx`});
        """.trimIndent()
    }
}

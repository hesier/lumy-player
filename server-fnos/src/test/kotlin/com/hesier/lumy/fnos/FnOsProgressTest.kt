package com.hesier.lumy.fnos

import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.AudioStream
import com.hesier.lumy.core.SubtitleStream
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.PlaybackMode
import com.hesier.lumy.core.PlaybackRequest
import com.hesier.lumy.core.PlaybackState
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class FnOsProgressTest {
    private val web = MockWebServer()
    private val client = FnOsClient(FnOsClientInfo("Test", "1", "device", "Test"))

    @After
    fun close() = web.shutdown()

    private fun progress(): FnOsProgress {
        web.enqueue(MockResponse().setBody("""<script src="/v/sign.js"></script>"""))
        web.enqueue(MockResponse().setBody(FIXTURE))
        val session = ServerSession(ServerKind.FNOS, "server", "Test", web.url("/").toString().trimEnd('/'), "test-token")
        return FnOsProgress(FnOsNativeClient(session, client))
    }

    private fun request() = PlaybackRequest(
        MediaItem("file-id", MediaType.MOVIE, "Movie"),
        MediaVersion("source-item", 0, null, null, null, emptyList(), emptyList(), emptyList()),
        PlaybackMode.Direct, 0, "play-session", audioStreamId = "audio:0", subtitleStreamId = null,
    )

    private fun data(value: String) = web.enqueue(MockResponse().setBody("""{"code":0,"data":$value}"""))

    private fun discardBootstrap() = repeat(2) { web.takeRequest() }

    @Test
    fun `every state records canonical and file IDs while preserving native track preferences`() = runBlocking {
        val progress = progress()
        data(INFO)
        repeat(PlaybackState.entries.size) { data("true") }
        PlaybackState.entries.forEach { progress.report(request(), it, 12345, 200500) }
        discardBootstrap()
        val info = web.takeRequest()
        assertEquals("/v/api/v1/play/info", info.path)
        assertEquals("source-item", client.json.parseToJsonElement(info.body.readUtf8()).jsonObject["item_guid"]!!.jsonPrimitive.content)
        repeat(PlaybackState.entries.size) {
            val record = web.takeRequest()
            assertEquals("/v/api/v1/play/record", record.path)
            val body = client.json.parseToJsonElement(record.body.readUtf8()).jsonObject
            assertEquals("canonical-id", body["item_guid"]!!.jsonPrimitive.content)
            assertEquals("file-id", body["media_guid"]!!.jsonPrimitive.content)
            assertEquals("12", body["ts"]!!.toString())
            assertEquals("200", body["duration"]!!.toString())
            assertEquals("native-audio", body["audio_guid"]!!.jsonPrimitive.content)
            assertEquals("native-video", body["video_guid"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, body["subtitle_guid"])
            assertFalse(body.toString().contains("audio:0"))
        }
        assertEquals(7, web.requestCount)
    }

    @Test
    fun `native track choices and explicit subtitle off are saved with playback progress`() = runBlocking {
        val progress = progress()
        data(INFO)
        repeat(2) { data("true") }
        val version = request().version.copy(
            audio = listOf(AudioStream("selected-audio", 2, "aac", 2, "en", "English", true)),
            subtitles = listOf(SubtitleStream("selected-subtitle", 3, "srt", "en", "English", false, true, null)),
        )
        val selected = request().copy(version = version, audioStreamId = "selected-audio", subtitleStreamId = "selected-subtitle")
        progress.report(selected, PlaybackState.PLAYING, 30000, 900000)
        progress.report(selected.copy(subtitleStreamId = null), PlaybackState.STOPPED, 31000, 900000)
        discardBootstrap()
        web.takeRequest()
        val first = client.json.parseToJsonElement(web.takeRequest().body.readUtf8()).jsonObject
        val last = client.json.parseToJsonElement(web.takeRequest().body.readUtf8()).jsonObject
        assertEquals("selected-audio", first["audio_guid"]!!.jsonPrimitive.content)
        assertEquals("selected-subtitle", first["subtitle_guid"]!!.jsonPrimitive.content)
        assertEquals("_no_display_", last["subtitle_guid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `record uses currently playing file when native info remembers another version`() = runBlocking {
        val progress = progress()
        data(INFO.replace("file-id", "previous-version-file"))
        data("true")
        progress.report(request(), PlaybackState.PLAYING, 30000, 900000)
        discardBootstrap()
        web.takeRequest()
        val body = client.json.parseToJsonElement(web.takeRequest().body.readUtf8()).jsonObject
        assertEquals("file-id", body["media_guid"]!!.jsonPrimitive.content)
        assertEquals("canonical-id", body["item_guid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stopped clears context only after accepted record and fallback duration comes from info`() = runBlocking {
        val progress = progress()
        data(INFO.replace("900", "900.75"))
        data("false")
        data("true")
        data(INFO)
        data("true")
        try {
            progress.report(request(), PlaybackState.STOPPED, 30000, 0)
            fail("Expected rejected record")
        } catch (_: IOException) {
            // The retry must retain the original native track context.
        }
        progress.report(request(), PlaybackState.STOPPED, 31000, -1)
        progress.report(request(), PlaybackState.PLAYING, -100, 0)
        discardBootstrap()
        assertEquals("/v/api/v1/play/info", web.takeRequest().path)
        repeat(2) {
            val body = client.json.parseToJsonElement(web.takeRequest().body.readUtf8()).jsonObject
            assertEquals("900", body["duration"]!!.toString())
        }
        assertEquals("/v/api/v1/play/info", web.takeRequest().path)
        val body = client.json.parseToJsonElement(web.takeRequest().body.readUtf8()).jsonObject
        assertEquals(0.0, body["ts"]!!.jsonPrimitive.double, 0.0)
    }

    @Test
    fun `reports serialize context lookup and writes for one session`() = runBlocking {
        val progress = progress()
        data(INFO)
        repeat(2) { data("true") }
        val first = async { progress.report(request(), PlaybackState.PLAYING, 10000, 100000) }
        val second = async { progress.report(request(), PlaybackState.PAUSED, 20000, 100000) }
        first.await()
        second.await()
        discardBootstrap()
        assertEquals(listOf("/v/api/v1/play/info", "/v/api/v1/play/record", "/v/api/v1/play/record"),
            (1..3).map { web.takeRequest().path })
    }

    @Test
    fun `resume reads fresh records filters completed items and resolves missing file ID`() = runBlocking {
        val progress = progress()
        data("""[
            {"guid":"movie","media_guid":"movie-file","ts":12.5,"duration":900,"type":"Movie","watched":0},
            {"guid":"episode","ts":30,"type":"Episode","watched":0},
            {"guid":"zero","media_guid":"zero-file","ts":0},
            {"guid":"watched","ts":30,"watched":1},
            {"guid":"watched-boolean","ts":30,"watched":true},
            {"guid":"finished","ts":900,"duration":900},
            {"guid":"unknown-duration","media_guid":"other-file","ts":5,"duration":0}
        ]""")
        data(INFO)
        data("""[{"guid":"movie","media_guid":"movie-file","ts":33,"duration":900}]""")
        assertEquals(listOf(FnOsResume("movie", "movie-file", 12500, 900000),
            FnOsResume("episode", "file-id", 30000, 900000), FnOsResume("unknown-duration", "other-file", 5000, null)),
            progress.resume())
        assertEquals(listOf(FnOsResume("movie", "movie-file", 33000, 900000)), progress.resume())
        discardBootstrap()
        assertEquals("/v/api/v1/play/list", web.takeRequest().path)
        val lookup = web.takeRequest()
        assertEquals("/v/api/v1/play/info", lookup.path)
        assertEquals("episode", client.json.parseToJsonElement(lookup.body.readUtf8()).jsonObject["item_guid"]!!.jsonPrimitive.content)
        assertEquals("/v/api/v1/play/list", web.takeRequest().path)
    }

    companion object {
        private const val INFO = """{"guid":"canonical-id","media_guid":"file-id","item":{"duration":900},"video_guid":"native-video","audio_guid":"native-audio","subtitle_guid":null}"""
        private val FIXTURE = """
            var demo=(()=>{let e=new Uint8Array([103,104,121,117,116,115,100,44,106,100,120]),t=new Uint8Array(11);for(let n=0;n<11;n++)t[n]=e[n]^1;return new TextDecoder(`utf-8`).decode(t)})();
            var sign=(r,s,c,o,t)=>{let l=[`fixture-app`,r,s,c,o,t].join(`_`);return `nonce=signature`;};
            client({apiKey:demo,header:`authx`});
        """.trimIndent()
    }
}

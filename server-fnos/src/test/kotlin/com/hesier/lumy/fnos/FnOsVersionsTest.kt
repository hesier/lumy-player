package com.hesier.lumy.fnos

import com.hesier.lumy.core.HdrType
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FnOsVersionsTest {
    private val fixture = FnOsTestDispatcher()
    private val web = MockWebServer().apply { dispatcher = fixture }
    private val client = FnOsClient(FnOsClientInfo("Test", "1", "device", "TV"))

    @After fun close() = web.shutdown()

    @Test fun `native files sharing one source retain unique versions and independent streams`() = runBlocking {
        fixture.files = """[{"media_guid":"file-b"},{"media_guid":"file-a"},{"media_guid":"file-b"}]"""
        fixture.streamsByFile["file-a"] = streams("file-a", 3840, "HDR10")
        fixture.streamsByFile["file-b"] = streams("file-b", 1920, "SDR")
        enqueue(item("file-a"))
        enqueue(item("file-b"))
        val server = FnOsMediaServer(
            ServerSession(ServerKind.FNOS, "server", "TV", web.url("/").toString().trimEnd('/'), "token"), client,
        )
        val detail = server.detail("file-a")
        assertEquals(listOf("file-a", "file-b"), detail.versions.map { it.id })
        assertEquals(listOf(0, 1), detail.versions.map { it.index })
        assertEquals(listOf("canonical", "canonical"), detail.versions.map { it.fnOsItemId })
        assertEquals(listOf(HdrType.HDR10, HdrType.NONE), detail.versions.map { it.video!!.hdr })
        assertEquals(listOf(3840, 1920), detail.versions.map { it.video!!.width })
        for (version in detail.versions) {
            assertEquals("Videos/${version.id}/stream", version.part.key)
            assertEquals("audio-${version.id}", version.audio.single().id)
            assertEquals("Videos/${version.id}/canonical/Subtitles/0/0/Stream.srt", version.subtitles.single().key)
        }
        val selections = fixture.requests.filter { it.path == "/v/api/v1/play/info" }.drop(1)
        assertEquals(listOf("file-a", "file-b"), selections.map {
            client.json.parseToJsonElement(it.body.readUtf8()).jsonObject["media_guid"]!!.jsonPrimitive.content
        })
        assertTrue(fixture.requests.any { it.path == "/v/api/v1/media/itemfile/canonical" })
    }

    private fun enqueue(body: String) = web.enqueue(MockResponse().setBody(body))

    private fun item(fileId: String) = """{"Id":"$fileId","Type":"Movie","MediaSources":[
        {"Id":"canonical","Path":"/$fileId.mkv","RunTimeTicks":1200000000}]}"""

    private fun streams(fileId: String, width: Int, range: String) = """{
        "video_stream":{"guid":"video-$fileId","media_guid":"$fileId","codec_name":"hevc",
        "width":$width,"height":1080,"duration":120,"color_range_type":"$range"},
        "audio_streams":[{"guid":"audio-$fileId","codec_name":"aac"}],
        "subtitle_streams":[{"guid":"subtitle-$fileId","codec_name":"subrip","format":"srt","is_external":1}]}"""
}

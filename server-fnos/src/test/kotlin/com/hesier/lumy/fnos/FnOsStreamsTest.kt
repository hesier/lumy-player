package com.hesier.lumy.fnos

import com.hesier.lumy.core.HdrType
import com.hesier.lumy.core.MediaVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FnOsStreamsTest {
    private val client = FnOsClient(FnOsClientInfo("Test", "1", "device", "Box"))

    @Test
    fun `native metadata replaces compatibility placeholders and keeps file and canonical identities`() {
        val previous = compatVersion()
        val version = previous.withFnOsStreams(nativeStreams())
        assertEquals("canonical-item", version.id)
        assertEquals(previous.part.id, version.part.id)
        assertEquals(previous.part.key, version.part.key)
        assertEquals("mkv", version.container)
        assertEquals("mkv", version.part.container)
        assertEquals(12_345L, version.part.durationMs)
        assertEquals(18_000, version.bitrateKbps)
        with(version.video!!) {
            assertEquals("hevc", codec)
            assertEquals(3840, width)
            assertEquals(2160, height)
            assertEquals(23.98f, frameRate!!, 0.001f)
            assertEquals(10, bitDepth)
            assertEquals("Main 10", profile)
            assertEquals(HdrType.DOLBY_VISION, hdr)
            assertEquals(8, doviProfile)
        }
        assertEquals(listOf("audio-guid-a", "audio-guid-b"), version.audio.map { it.id })
        assertEquals(listOf(2, 5), version.audio.map { it.index })
        assertEquals(listOf(false, true), version.audio.map { it.selected })
        assertEquals(48_000, version.audio.first().sampleRate)
        assertEquals(640, version.audio.first().bitrateKbps)
        assertEquals("Native audio", version.audio.first().title)
        assertEquals("eng · AAC", version.audio.last().title)
        assertEquals(listOf("subtitle-guid-a", "subtitle-guid-b"), version.subtitles.map { it.id })
        assertEquals(listOf(7, null), version.subtitles.map { it.index })
        assertEquals(listOf("pgs", "pgs"), version.subtitles.map { it.codec })
        assertNull(version.subtitles.first().key)
        assertEquals("Videos/file-item/canonical-item/Subtitles/1/0/Stream.sup", version.subtitles.last().key)
        assertTrue(version.subtitles.first().forced)
        assertTrue(version.subtitles.last().external)
    }

    @Test
    fun `empty or absent native streams preserve compatibility data`() {
        val version = compatVersion()
        assertEquals(version, version.withFnOsStreams(json("{}")))
        assertEquals(version, version.withFnOsStreams(json(
            """{"video_stream":{},"audio_streams":[],"subtitle_streams":[]}""",
        )))
        assertEquals(version, version.withFnOsStreams(json(
            """{"video_stream":null,"audio_streams":null,"subtitle_streams":null}""",
        )))
    }

    @Test
    fun `partial native streams keep known compatibility codec index and duration`() {
        val previous = compatVersion().withFnOsStreams(nativeStreams())
        val version = previous.withFnOsStreams(json(
            """{"video_stream":{"duration":0},"audio_streams":[{"guid":"updated-guid"}]}""",
        ))
        assertEquals(previous.video, version.video)
        assertEquals(previous.part.durationMs, version.part.durationMs)
        assertEquals(previous.audio.first().copy(id = "updated-guid"), version.audio.single())
        assertEquals(previous.subtitles, version.subtitles)
    }

    @Test
    fun `play info selects by GUID and empty subtitle GUID disables every subtitle`() {
        val version = compatVersion().withFnOsStreams(nativeStreams())
        val selected = version.withFnOsStreamSelection(json(
            """{"audio_guid":"audio-guid-a","subtitle_guid":"subtitle-guid-a"}""",
        ))
        assertEquals(listOf(true, false), selected.audio.map { it.selected })
        assertEquals(listOf(true, false), selected.subtitles.map { it.selected })
        val disabled = selected.withFnOsStreamSelection(json("""{"subtitle_guid":""}"""))
        assertTrue(disabled.subtitles.none { it.selected })
        assertEquals(selected.audio, disabled.audio)
        assertEquals(version, version.withFnOsStreamSelection(null))
    }

    @Test
    fun `HDR labels and fractional frame rate map without relying on compatibility hints`() {
        val previous = compatVersion()
        for ((label, type) in listOf(
            "SDR" to HdrType.NONE, "HDR10" to HdrType.HDR10, "HDR10+" to HdrType.HDR10_PLUS,
            "HLG" to HdrType.HLG, "DolbyVision" to HdrType.DOLBY_VISION,
        )) {
            val version = previous.withFnOsStreams(json(
                """{"video_stream":{"color_range_type":"$label","r_frame_rate":"24000/1001"}}""",
            ))
            assertEquals(type, version.video!!.hdr)
            assertEquals(23.976f, version.video!!.frameRate!!, 0.001f)
        }
    }

    @Test
    fun `external text subtitle uses native ordinal and format rather than ffprobe index`() {
        val version = compatVersion().withFnOsStreams(json(
            """{"subtitle_streams":[{"guid":"srt-guid","index":23,"codec_name":"subrip",
                "format":"srt","is_external":1,"language":"zho","is_default":0}]}""",
        ))
        val subtitle = version.subtitles.single()
        assertEquals("srt", subtitle.codec)
        assertEquals("Videos/file-item/canonical-item/Subtitles/0/0/Stream.srt", subtitle.key)
        assertFalse(subtitle.selected)
        assertNull(subtitle.index)
    }

    private fun compatVersion(): MediaVersion = client.json.decodeFromString<BaseItem>(
        """{"Id":"file-item","Type":"Movie","MediaSources":[{"Id":"canonical-item",
            "Container":"mp4","RunTimeTicks":300000000,"MediaStreams":[
            {"Type":"Video","Codec":"h264","Width":1920,"Height":1080},
            {"Type":"Audio","Codec":"ac3","Index":0,"IsDefault":true}]}]}""",
    ).fnOsDetail().versions.single()

    private fun nativeStreams() = json(
        """{"video_stream":{"guid":"video-guid","media_guid":"file-item","index":0,
            "codec_name":"hevc","width":3840,"height":2160,"duration":12.345,"bit_depth":10,
            "dv_profile":8,"bps":18000000,"wrapper":"mkv","color_range_type":"HDR10",
            "r_frame_rate":"23.98 fps","profile":"Main 10"},"audio_streams":[
            {"guid":"audio-guid-a","index":2,"codec_name":"eac3","channels":6,"title":"Native audio",
            "is_default":0,"sample_rate":"48000","bps":640000},
            {"guid":"audio-guid-b","index":5,"codec_name":"aac","channels":2,"language":"eng","is_default":1}],
            "subtitle_streams":[{"guid":"subtitle-guid-a","index":7,"codec_name":"hdmv_pgs_subtitle",
            "forced":1,"is_external":0,"is_default":0},{"guid":"subtitle-guid-b","index":9,
            "codec_name":"hdmv_pgs_subtitle","format":"sup","is_bitmap":1,"is_external":1,"is_default":1}]}""",
    )

    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
}

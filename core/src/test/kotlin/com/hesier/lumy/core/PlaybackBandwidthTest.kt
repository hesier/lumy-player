package com.hesier.lumy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackBandwidthTest {
    @Test
    fun `bandwidth reserves twenty five percent and defaults to unlimited`() {
        assertEquals(750, PlaybackBandwidth.maxTotalKbps(1))
        assertEquals(7_500, PlaybackBandwidth.maxTotalKbps(10))
        assertEquals(75_000, PlaybackBandwidth.maxTotalKbps(100))
        assertNull(PlaybackBandwidth.maxTotalKbps(0))
        assertNull(PlaybackBandwidth.maxTotalKbps(-1))
        assertEquals(Int.MAX_VALUE, PlaybackBandwidth.maxTotalKbps(Int.MAX_VALUE))
    }

    @Test
    fun `file size and duration estimate the complete source bitrate`() {
        val source = version().copy(parts = listOf(part(12_000_000, 60_000)))
        assertEquals(1_600, PlaybackBandwidth.sourceKbps(source))
        assertFalse(PlaybackBandwidth.requiresTranscode(source, 1_600))
        assertTrue(PlaybackBandwidth.requiresTranscode(source, 1_599))
    }

    @Test
    fun `multiple files use the highest part bitrate instead of an average`() {
        val source = version().copy(parts = listOf(part(12_000_000, 60_000), part(8_000_000, 20_000)))
        assertEquals(3_200, PlaybackBandwidth.sourceKbps(source))
        assertTrue(PlaybackBandwidth.requiresTranscode(source, 3_000))
    }

    @Test
    fun `incomplete or invalid file measurements do not establish source bitrate`() {
        for (invalid in listOf(part(null, 1_000), part(1_000, null), part(0, 1_000), part(1_000, 0), part(-1, 1_000))) {
            assertNull(PlaybackBandwidth.sourceKbps(version().copy(parts = listOf(invalid))))
            assertNull(PlaybackBandwidth.sourceKbps(version().copy(parts = listOf(part(1_000, 1_000), invalid))))
        }
    }

    @Test
    fun `source bitrate includes every downloaded audio track and uses the largest estimate`() {
        val source = version().copy(
            bitrateKbps = 4_000,
            video = video(4_000),
            audio = listOf(audio("first", 128), audio("selected", 640, true)),
            parts = listOf(part(500_000, 1_000)),
        )
        assertEquals(4_768, PlaybackBandwidth.sourceKbps(source))
        assertEquals(5_000, PlaybackBandwidth.sourceKbps(source.copy(bitrateKbps = 5_000)))
        assertEquals(8_000, PlaybackBandwidth.sourceKbps(source.copy(parts = listOf(part(1_000_000, 1_000)))))
    }

    @Test
    fun `unknown bitrate requires transcode only when bandwidth is limited`() {
        val unknown = version()
        assertNull(PlaybackBandwidth.sourceKbps(unknown))
        assertTrue(PlaybackBandwidth.requiresTranscode(unknown, 7_500))
        assertFalse(PlaybackBandwidth.requiresTranscode(unknown, null))
        assertNull(PlaybackBandwidth.sourceKbps(unknown.copy(bitrateKbps = 0)))
        assertNull(PlaybackBandwidth.sourceKbps(unknown.copy(video = video(4_000), audio = listOf(audio("a", null)))))
        assertNull(PlaybackBandwidth.sourceKbps(unknown.copy(video = video(0))))
        assertEquals(4_000, PlaybackBandwidth.sourceKbps(unknown.copy(video = video(4_000))))
    }

    @Test
    fun `source estimates saturate instead of overflowing`() {
        assertEquals(Int.MAX_VALUE, PlaybackBandwidth.sourceKbps(version().copy(parts = listOf(part(Long.MAX_VALUE, 1)))))
        val source = version().copy(
            video = video(Int.MAX_VALUE),
            audio = listOf(audio("a", Int.MAX_VALUE), audio("b", Int.MAX_VALUE)),
        )
        assertEquals(Int.MAX_VALUE, PlaybackBandwidth.sourceKbps(source))
    }

    @Test
    fun `unlimited or direct requests have no transcoding budget`() {
        assertNull(PlaybackBandwidth.budget(request(PlaybackMode.Direct)))
        assertNull(PlaybackBandwidth.budget(request(PlaybackMode.Transcode())))
        assertNull(PlaybackBandwidth.budget(request(PlaybackMode.Transcode(maxBitrateKbps = 2_000))))
    }

    @Test
    fun `explicit audio selection takes precedence over selected and first tracks`() {
        val tracks = listOf(audio("first", 128), audio("selected", 192, true), audio("explicit", 256))
        val mode = PlaybackMode.Transcode(maxTotalBitrateKbps = 4_000, copyAudio = true)
        assertEquals(PlaybackBandwidth.Budget(3_744, 256, true), PlaybackBandwidth.budget(request(mode, tracks, "explicit")))
        assertEquals(PlaybackBandwidth.Budget(3_808, 192, true), PlaybackBandwidth.budget(request(mode, tracks)))
        assertEquals(PlaybackBandwidth.Budget(3_808, 192, true), PlaybackBandwidth.budget(request(mode, tracks, "missing")))
        val unselected = tracks.map { it.copy(selected = false) }
        assertEquals(PlaybackBandwidth.Budget(3_872, 128, true), PlaybackBandwidth.budget(request(mode, unselected)))
    }

    @Test
    fun `unknown or excessive original audio bitrate disables stream copy`() {
        val mode = PlaybackMode.Transcode(maxTotalBitrateKbps = 4_000, copyAudio = true)
        for (rate in listOf(null, 0, -1, 385, 6_000)) {
            assertEquals(
                PlaybackBandwidth.Budget(3_616, 384, false),
                PlaybackBandwidth.budget(request(mode, listOf(audio("a", rate)))),
            )
        }
        assertEquals(PlaybackBandwidth.Budget(3_616, 384, false), PlaybackBandwidth.budget(request(mode)))
        assertEquals(
            PlaybackBandwidth.Budget(3_616, 384, true),
            PlaybackBandwidth.budget(request(mode, listOf(audio("a", 384)))),
        )
        assertEquals(
            PlaybackBandwidth.Budget(3_616, 384, false),
            PlaybackBandwidth.budget(request(mode.copy(copyAudio = false), listOf(audio("a", 128)))),
        )
    }

    @Test
    fun `audio reserve adapts to a small total and preserves a positive video budget`() {
        assertEquals(
            PlaybackBandwidth.Budget(600, 200, false),
            PlaybackBandwidth.budget(request(PlaybackMode.Transcode(maxTotalBitrateKbps = 800))),
        )
        assertEquals(
            PlaybackBandwidth.Budget(1, 1, false),
            PlaybackBandwidth.budget(request(PlaybackMode.Transcode(maxTotalBitrateKbps = 2))),
        )
        assertEquals(
            PlaybackBandwidth.Budget(3_232, 768, false),
            PlaybackBandwidth.budget(request(PlaybackMode.Transcode(maxTotalBitrateKbps = 4_000)), encodedAudioKbps = 768),
        )
    }

    @Test
    fun `manual video limit and total remaining budget use the lower ceiling`() {
        val mode = PlaybackMode.Transcode(maxTotalBitrateKbps = 4_000, maxBitrateKbps = 2_000)
        assertEquals(PlaybackBandwidth.Budget(2_000, 384, false), PlaybackBandwidth.budget(request(mode)))
        assertEquals(
            PlaybackBandwidth.Budget(3_616, 384, false),
            PlaybackBandwidth.budget(request(mode.copy(maxBitrateKbps = 8_000))),
        )
    }

    @Test
    fun `known oversized part is honored even when another part is unknown`() {
        val source = version().copy(bitrateKbps = 1000, parts = listOf(part(500_000, 1000), part(null, null)))
        assertTrue(PlaybackBandwidth.requiresTranscode(source, 1500))
        assertEquals(4_000, PlaybackBandwidth.sourceKbps(source, includePartialParts = true))
        assertEquals(1_000, PlaybackBandwidth.sourceKbps(source))
    }

    @Test
    fun `audio expansion can require video reencoding within the source budget`() {
        val source = version().copy(bitrateKbps = 1400, video = video(1250), audio = listOf(audio("a", 128)))
        val mode = PlaybackMode.Transcode(maxTotalBitrateKbps = 1500)
        val budget = PlaybackBandwidth.budget(mode, source.audio.single())!!
        assertEquals(PlaybackBandwidth.Budget(1125, 375, false), budget)
        assertFalse(budget.canCopyVideo(source))
        assertTrue(budget.canCopyVideo(source.copy(video = video(1000))))
        assertFalse(budget.canCopyVideo(version()))
    }

    private fun version() = MediaVersion("version", 0, "mkv", null, null, emptyList(), emptyList(), emptyList())

    private fun part(size: Long?, duration: Long?) = MediaPart("part", "/file", "mkv", size, duration)

    private fun video(bitrate: Int) = VideoStream("h264", null, null, null, null, null, HdrType.NONE, null, bitrate)

    private fun audio(id: String, bitrate: Int?, selected: Boolean = false) = AudioStream(
        id = id, codec = "aac", channels = 2, language = null, title = id, selected = selected, bitrateKbps = bitrate,
    )

    private fun request(mode: PlaybackMode, audio: List<AudioStream> = emptyList(), audioId: String? = null) = PlaybackRequest(
        item = MediaItem("item", MediaType.MOVIE, "Movie"),
        version = version().copy(audio = audio),
        mode = mode,
        startMs = 0,
        sessionId = "session",
        audioStreamId = audioId,
    )
}

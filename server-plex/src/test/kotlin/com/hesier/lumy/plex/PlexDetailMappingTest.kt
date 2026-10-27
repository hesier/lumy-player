package com.hesier.lumy.plex

import com.hesier.lumy.core.HdrType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 结构取自真实服务器的 /library/metadata 响应（已精简） */
class PlexDetailMappingTest {
    private val client = PlexClient(PlexClientInfo("Test", "1", "id", "12", "Emu"))

    private fun parse(json: String) = client.json.decodeFromString<Envelope>(json).container.metadata.single()

    @Test
    fun `4K dolby vision movie maps versions and streams`() {
        val detail = parse(
            """
            {"MediaContainer":{"Metadata":[{"ratingKey":"64418","type":"movie","title":"Z","year":2025,"audienceRating":9.2,
              "Genre":[{"tag":"喜剧"},{"tag":"动画"}],"Director":[{"tag":"Jared Bush"}],
              "Media":[{"id":67158,"bitrate":26712,"container":"mkv","videoCodec":"hevc","Part":[{"id":95612,
                "key":"/library/parts/95612/1770173032/file.mkv","container":"mkv","size":21564670565,"duration":6458464,"Stream":[
                {"id":290941,"streamType":1,"codec":"hevc","index":0,"bitDepth":10,"colorTrc":"smpte2084","DOVIProfile":8,"DOVIPresent":true,"frameRate":24.0,"width":3840,"height":2160},
                {"id":290942,"streamType":2,"codec":"eac3","index":1,"extendedDisplayTitle":"English (EAC3 5.1 + Atmos)","languageCode":"eng","channels":6},
                {"id":290943,"streamType":2,"codec":"eac3","index":2,"extendedDisplayTitle":"Mandarin-TW (中文 EAC3 7.1)","languageCode":"zho","selected":true,"channels":8},
                {"id":290945,"streamType":3,"codec":"pgs","index":4,"extendedDisplayTitle":"chs&eng (中文 PGS)","languageCode":"zho","selected":true},
                {"id":290990,"streamType":3,"codec":"srt","key":"/library/streams/290990","extendedDisplayTitle":"English (SRT External)","languageCode":"eng"}
              ]}]}]}]}}
            """.trimIndent(),
        ).toDetail(client.json)

        val v = detail.versions.single()
        assertEquals(HdrType.DOLBY_VISION, v.video!!.hdr)
        assertEquals(8, v.video!!.doviProfile)
        assertEquals("4K · HEVC · Dolby Vision · EAC3 7.1", v.summary)
        assertEquals("290943", v.audio.single { it.selected }.id)
        assertEquals(listOf(false, true), v.subtitles.map { it.external })
        assertEquals("/library/parts/95612/1770173032/file.mkv", v.part.key)
        assertEquals(listOf("喜剧", "动画"), detail.genres)
        assertEquals(9.2, detail.rating!!, 0.0)
    }

    @Test
    fun `stream language prefers languageTag over languageCode`() {
        val v = parse(
            """{"MediaContainer":{"Metadata":[{"ratingKey":"1","type":"movie","Media":[{"id":1,"Part":[{"id":2,"key":"/p","Stream":[
              {"id":10,"streamType":3,"codec":"srt","index":2,"extendedDisplayTitle":"中文 (SRT)","languageCode":"chi","languageTag":"zh-Hant"},
              {"id":11,"streamType":3,"codec":"srt","index":3,"extendedDisplayTitle":"English (SRT)","languageCode":"eng"}
            ]}]}]}]}}""",
        ).toDetail(client.json).versions.single()
        assertEquals(listOf("zh-Hant", "eng"), v.subtitles.map { it.language })
    }

    @Test
    fun `on deck accepts a single object`() {
        val show = parse(
            """{"MediaContainer":{"Metadata":[{"ratingKey":"8955","type":"show","title":"S",
              "OnDeck":{"Metadata":{"ratingKey":"64462","type":"episode","title":"E","parentRatingKey":"64461","grandparentRatingKey":"8955","index":1,"parentIndex":2}}}]}}""",
        ).toDetail(client.json)
        assertEquals("64462", show.onDeck!!.id)
        assertEquals("64461", show.onDeck!!.parentId)
        assertTrue(show.versions.isEmpty())
    }

    @Test
    fun `on deck accepts an array and absence`() {
        val arr = parse("""{"MediaContainer":{"Metadata":[{"ratingKey":"1","type":"show","OnDeck":{"Metadata":[{"ratingKey":"2","type":"episode"}]}}]}}""")
        assertEquals("2", arr.toDetail(client.json).onDeck!!.id)
        assertNull(parse("""{"MediaContainer":{"Metadata":[{"ratingKey":"1","type":"show"}]}}""").toDetail(client.json).onDeck)
    }
}

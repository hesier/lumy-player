package com.hesier.lumy.plex

import com.hesier.lumy.core.HubStyle
import com.hesier.lumy.core.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexMappingTest {
    private val client = PlexClient(PlexClientInfo("Test", "1", "id", "12", "Emu"))

    @Test
    fun `episode maps to show poster and episode thumb`() {
        val json = """
            {"MediaContainer":{"size":1,"Hub":[{"hubIdentifier":"home.continue","title":"Continue Watching","type":"mixed",
              "Metadata":[{"ratingKey":"42","type":"episode","title":"Pilot","grandparentTitle":"Lost",
                "index":1,"parentIndex":1,"thumb":"/ep/thumb","grandparentThumb":"/show/thumb","grandparentArt":"/show/art",
                "duration":2600000,"viewOffset":1300000,"someNewField":true}]}]}}
        """.trimIndent()
        val hub = client.json.decodeFromString<Envelope>(json).container.hubs.single().toHub()!!
        val item = hub.items.single()

        assertEquals(HubStyle.LANDSCAPE, hub.style)
        assertEquals(MediaType.EPISODE, item.type)
        assertEquals("Lost", item.showTitle)
        assertEquals("/show/thumb", item.poster)
        assertEquals("/ep/thumb", item.thumb)
        assertEquals(0.5f, item.progress!!, 0.001f)
    }

    @Test
    fun `show watched state comes from leaf counts`() {
        val json = """{"ratingKey":"7","type":"show","title":"X","leafCount":10,"viewedLeafCount":4}"""
        val item = client.json.decodeFromString<PlexMetadata>(json).toItem()
        assertFalse(item.watched)
        assertEquals(6, item.unwatchedCount)

        val done = client.json.decodeFromString<PlexMetadata>(json.replace("\"viewedLeafCount\":4", "\"viewedLeafCount\":10")).toItem()
        assertTrue(done.watched)
    }

    @Test
    fun `recently added movies use poster style`() {
        val json = """{"hubIdentifier":"movie.recentlyadded.1","title":"Recently Added","Metadata":[{"ratingKey":"1","type":"movie","title":"M"}]}"""
        assertEquals(HubStyle.POSTER, client.json.decodeFromString<PlexHub>(json).toHub()!!.style)
    }

    @Test
    fun `resource provides list is parsed`() {
        val json = """{"name":"NAS","clientIdentifier":"abc","provides":"server,player","accessToken":"t",
            "connections":[{"protocol":"http","uri":"http://192.168.1.2:32400","local":true,"IPv6":false}]}"""
        val r = client.json.decodeFromString<PlexResource>(json)
        assertTrue(r.isServer)
        assertTrue(r.connections.single().local)
    }
}

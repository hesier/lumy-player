package com.hesier.lumy.fnos

import com.hesier.lumy.core.Marker
import com.hesier.lumy.core.MarkerType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FnOsMarkersTest {
    private val intro = Marker(MarkerType.INTRO, 10_000, 30_000)
    private val credits = Marker(MarkerType.CREDITS, 1_300_000, 1_364_000)
    private val existing = listOf(intro, credits)

    @Test fun `native seconds become intro and final seconds for each episode`() {
        assertEquals(listOf(Marker(MarkerType.INTRO, 0, 46_000), Marker(MarkerType.CREDITS, 1_324_000, 1_364_000)),
            markers("""{"skip_opening":46,"skip_ending":40}"""))
        assertEquals(listOf(Marker(MarkerType.INTRO, 0, 46_000), Marker(MarkerType.CREDITS, 1_344_000, 1_384_000)),
            markers("""{"skip_opening":46,"skip_ending":40}""", duration = 1_384_000))
    }

    @Test fun `missing unset or malformed configuration preserves compatibility markers`() {
        assertEquals(existing, fnOsMarkers(null, 1_364_000, existing))
        assertEquals(existing, fnOsMarkers(Json.parseToJsonElement("{}").jsonObject, 1_364_000, existing))
        for (config in listOf("null", "[]", "{}", """{"skip_opening":null,"skip_ending":null}""")) {
            assertEquals(existing, markers(config, previous = existing))
        }
    }

    @Test fun `only valid native types replace their compatibility counterparts`() {
        assertEquals(listOf(Marker(MarkerType.INTRO, 0, 46_000), credits),
            markers("""{"skip_opening":46}""", previous = existing))
        assertEquals(listOf(intro, Marker(MarkerType.CREDITS, 1_324_000, 1_364_000)),
            markers("""{"skip_ending":40}""", previous = existing))
    }

    @Test fun `invalid and overflowing seconds never become full video skips`() {
        for (value in listOf("0", "-1", "1364", "5000", "9223372036854775807", "1e300", "true", "\"bad\"")) {
            assertEquals(existing, markers("""{"skip_opening":$value,"skip_ending":$value}""", previous = existing))
        }
    }

    @Test fun `unknown duration permits intro but cannot locate credits`() {
        for (duration in listOf(null, 0L, -1L)) {
            assertEquals(listOf(Marker(MarkerType.INTRO, 0, 46_000)),
                markers("""{"skip_opening":46,"skip_ending":40}""", duration = duration))
        }
    }

    @Test fun `overlapping native ranges preserve intro without skipping remaining content`() {
        for (duration in listOf(60_000L, 86_000L)) {
            assertEquals(listOf(Marker(MarkerType.INTRO, 0, 46_000)),
                markers("""{"skip_opening":46,"skip_ending":40}""", duration = duration))
        }
    }

    private fun markers(config: String, duration: Long? = 1_364_000, previous: List<Marker> = emptyList()) =
        fnOsMarkers(Json.parseToJsonElement("""{"play_config":$config}""").jsonObject, duration, previous)
}

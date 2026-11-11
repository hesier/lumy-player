package com.hesier.lumy.fnos

import com.hesier.lumy.core.Marker
import com.hesier.lumy.core.MarkerType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Native skip durations are seconds; the player consumes absolute millisecond ranges. */
internal fun fnOsMarkers(info: JsonObject?, durationMs: Long?, existing: List<Marker>): List<Marker> {
    val config = info?.get("play_config") as? JsonObject ?: return existing
    val duration = durationMs?.takeIf { it > 0 }
    val opening = config.skipMs("skip_opening")?.takeIf { duration == null || it < duration }
    val ending = config.skipMs("skip_ending")?.takeIf { duration != null && it < duration }
    val native = buildList {
        opening?.let { add(Marker(MarkerType.INTRO, 0, it)) }
        if (ending != null && duration != null && duration - ending > (opening ?: 0)) {
            add(Marker(MarkerType.CREDITS, duration - ending, duration))
        }
    }
    val replaced = native.map { it.type }.toSet()
    return (existing.filter { it.type !in replaced } + native).sortedBy { it.startMs }
}

private fun JsonObject.skipMs(key: String): Long? =
    (get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }
        ?.times(1000)

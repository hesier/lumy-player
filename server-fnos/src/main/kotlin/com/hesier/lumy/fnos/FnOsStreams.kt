package com.hesier.lumy.fnos

import com.hesier.lumy.core.AudioStream
import com.hesier.lumy.core.HdrType
import com.hesier.lumy.core.MediaPart
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.SubtitleStream
import com.hesier.lumy.core.VideoStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Native GUIDs identify selections; ffprobe indices identify tracks inside the file. */
internal fun MediaVersion.withFnOsStreams(data: JsonObject): MediaVersion {
    val nativeVideo = data["video_stream"] as? JsonObject
    val container = nativeVideo?.text("wrapper")?.substringBefore(',')?.lowercase() ?: container
    val duration = nativeVideo?.number("duration")?.takeIf { it > 0 && it.isFinite() }
        ?.let { (it * MILLIS_PER_SECOND).toLong() }
    val fileId = nativeVideo?.text("media_guid")
        ?: parts.firstOrNull()?.key?.substringAfter("Videos/")?.substringBefore('/') ?: id
    val audio = data.streams("audio_streams").mapIndexed { ordinal, stream ->
        stream.audio(this.audio.getOrNull(ordinal), ordinal)
    }
    val subtitles = data.streams("subtitle_streams").mapIndexed { ordinal, stream ->
        stream.subtitle(this.subtitles.getOrNull(ordinal), "Videos/$fileId/$id/Subtitles/$ordinal/0/Stream")
    }
    val sourceParts = parts.ifEmpty {
        listOf(MediaPart(fileId, "Videos/$fileId/stream", container, null, null))
    }
    return copy(
        container = container,
        bitrateKbps = nativeVideo?.kbps() ?: bitrateKbps,
        video = nativeVideo?.takeIf { it.isNotEmpty() }?.video(video) ?: video,
        audio = audio.ifEmpty { this.audio },
        subtitles = subtitles.ifEmpty { this.subtitles },
        parts = sourceParts.mapIndexed { index, part ->
            if (index == 0) part.copy(container = container, durationMs = duration ?: part.durationMs) else part
        },
    )
}

/** An empty native subtitle GUID explicitly disables subtitles. */
internal fun MediaVersion.withFnOsStreamSelection(info: JsonObject?): MediaVersion {
    if (info == null) return this
    return copy(
        audio = if (info.containsKey("audio_guid")) audio.map {
            it.copy(selected = it.id == info.text("audio_guid"))
        } else audio,
        subtitles = if (info.containsKey("subtitle_guid")) subtitles.map {
            it.copy(selected = it.id == info.text("subtitle_guid"))
        } else subtitles,
    )
}

private fun JsonObject.video(previous: VideoStream?): VideoStream = VideoStream(
    codec = text("codec_name") ?: previous?.codec.orEmpty(),
    width = positiveInt("width") ?: previous?.width,
    height = positiveInt("height") ?: previous?.height,
    frameRate = text("r_frame_rate")?.substringBefore(' ')?.frameRate() ?: previous?.frameRate,
    bitDepth = positiveInt("bit_depth") ?: previous?.bitDepth,
    profile = text("profile") ?: previous?.profile,
    hdr = hdr(previous?.hdr ?: HdrType.NONE),
    doviProfile = positiveInt("dv_profile") ?: previous?.doviProfile,
    bitrateKbps = kbps() ?: previous?.bitrateKbps,
    title = text("title") ?: previous?.title,
    language = text("language") ?: previous?.language,
)

private fun JsonObject.hdr(previous: HdrType): HdrType {
    val range = text("color_range_type")?.uppercase()?.replace(" ", "") ?: return when {
        positiveInt("dv_profile") != null -> HdrType.DOLBY_VISION
        else -> previous
    }
    return when {
        positiveInt("dv_profile") != null || "DOLBY" in range || "DOVI" in range || range == "DV" -> HdrType.DOLBY_VISION
        "HDR10+" in range || "HDR10PLUS" in range -> HdrType.HDR10_PLUS
        "HLG" in range -> HdrType.HLG
        "HDR" in range -> HdrType.HDR10
        range == "SDR" -> HdrType.NONE
        else -> previous
    }
}

private fun JsonObject.audio(previous: AudioStream?, ordinal: Int): AudioStream {
    val codec = text("codec_name") ?: previous?.codec.orEmpty()
    val language = text("language") ?: previous?.language
    return AudioStream(
        id = text("guid") ?: previous?.id ?: "audio:$ordinal",
        index = int("index")?.takeIf { it >= 0 } ?: previous?.index,
        codec = codec,
        channels = positiveInt("channels") ?: previous?.channels,
        language = language,
        title = text("title") ?: previous?.title ?: trackTitle(language, codec),
        selected = flag("is_default") ?: previous?.selected ?: false,
        bitrateKbps = kbps() ?: previous?.bitrateKbps,
        sampleRate = positiveInt("sample_rate") ?: previous?.sampleRate,
    )
}

private fun JsonObject.subtitle(previous: SubtitleStream?, route: String): SubtitleStream {
    val format = text("format")?.lowercase()
    val codec = when (val value = text("codec_name") ?: format ?: previous?.codec) {
        "hdmv_pgs_subtitle", "sup" -> "pgs"
        else -> subtitleCodec(value)
    }
    val language = text("language") ?: previous?.language
    val external = flag("is_external") ?: previous?.external ?: false
    return SubtitleStream(
        id = text("guid") ?: previous?.id ?: "subtitle:${route.substringAfter("Subtitles/").substringBefore('/')}",
        index = if (external) null else int("index")?.takeIf { it >= 0 } ?: previous?.index,
        codec = codec,
        language = language,
        title = text("title") ?: previous?.title ?: trackTitle(language, codec),
        forced = flag("forced") ?: previous?.forced ?: false,
        selected = flag("is_default") ?: previous?.selected ?: false,
        key = if (external) "$route.${format ?: codec}" else null,
    )
}

private fun JsonObject.streams(key: String): List<JsonObject> =
    (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.number(key: String): Double? = text(key)?.toDoubleOrNull()
private fun JsonObject.int(key: String): Int? = text(key)?.toIntOrNull()
private fun JsonObject.positiveInt(key: String): Int? = int(key)?.takeIf { it > 0 }
private fun JsonObject.kbps(): Int? = number("bps")?.takeIf { it > 0 && it.isFinite() }
    ?.let { (it / BITS_PER_KILOBIT).toInt() }

private fun JsonObject.flag(key: String): Boolean? = when (text(key)?.lowercase()) {
    "1", "true" -> true
    "0", "false" -> false
    else -> null
}

private fun String.frameRate(): Float? {
    val values = split('/')
    val numerator = values.firstOrNull()?.toFloatOrNull() ?: return null
    val rate = if (values.size == 2) numerator / (values[1].toFloatOrNull() ?: return null) else numerator
    return rate.takeIf { it > 0 && it.isFinite() }
}

private fun trackTitle(language: String?, codec: String): String =
    listOfNotNull(language?.takeIf { it.isNotBlank() }, codec.uppercase()).joinToString(" · ")

private const val MILLIS_PER_SECOND = 1_000
private const val BITS_PER_KILOBIT = 1_000

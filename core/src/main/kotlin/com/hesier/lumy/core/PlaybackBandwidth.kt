package com.hesier.lumy.core

/** A throughput budget, not a network traffic shaper. */
object PlaybackBandwidth {
    private const val KBPS_PER_MBPS = 1000
    private const val USABLE_PERCENT = 75
    private const val DEFAULT_AUDIO_KBPS = 384

    fun maxTotalKbps(bandwidthMbps: Int): Int? = bandwidthMbps.takeIf { it > 0 }?.let {
        (it.toLong() * KBPS_PER_MBPS * USABLE_PERCENT / 100).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun requiresTranscode(version: MediaVersion, maxTotalKbps: Int?): Boolean {
        if (maxTotalKbps == null) return false
        if (version.parts.any { (partKbps(it) ?: 0) > maxTotalKbps }) return true
        val sourceKbps = sourceKbps(version) ?: return true
        return sourceKbps > maxTotalKbps
    }

    fun sourceKbps(version: MediaVersion, includePartialParts: Boolean = false): Int? {
        val reported = version.bitrateKbps?.takeIf { it > 0 }
        // Direct Play downloads the whole file, including unselected tracks.
        val estimates = version.parts.map(::partKbps)
        val fileKbps = estimates.takeIf { it.isNotEmpty() && (includePartialParts || it.all { rate -> rate != null }) }?.filterNotNull()?.maxOrNull()
        val video = version.video?.bitrateKbps?.takeIf { it > 0 }
        val audio = version.audio.map { it.bitrateKbps?.takeIf { rate -> rate > 0 } }
        val tracksKbps = if (video != null && audio.all { it != null }) {
            (video.toLong() + audio.filterNotNull().sumOf { it.toLong() }).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else null
        return listOfNotNull(reported, fileKbps, tracksKbps).maxOrNull()
    }

    private fun partKbps(part: MediaPart): Int? {
        val size = part.sizeBytes?.takeIf { it > 0 } ?: return null
        val duration = part.durationMs?.takeIf { it > 0 } ?: return null
        return kotlin.math.ceil(size.toDouble() * 8 / duration).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    }

    data class Budget(val videoKbps: Int, val audioKbps: Int, val copyAudio: Boolean) {
        fun canCopyVideo(version: MediaVersion): Boolean {
            val rate = version.video?.bitrateKbps?.takeIf { it > 0 } ?: sourceKbps(version) ?: return false
            return rate <= videoKbps
        }
    }

    fun budget(request: PlaybackRequest, encodedAudioKbps: Int = DEFAULT_AUDIO_KBPS): Budget? {
        val mode = request.mode as? PlaybackMode.Transcode ?: return null
        val sourceAudio = request.version.audio.firstOrNull { it.id == request.audioStreamId }
            ?: request.version.audio.firstOrNull { it.selected } ?: request.version.audio.firstOrNull()
        return budget(mode, sourceAudio, encodedAudioKbps)
    }

    fun budget(mode: PlaybackMode.Transcode, sourceAudio: AudioStream?, encodedAudioKbps: Int = DEFAULT_AUDIO_KBPS): Budget? {
        val total = mode.maxTotalBitrateKbps ?: return null
        require(total >= 2)
        val reserve = minOf(encodedAudioKbps, total / 4).coerceAtLeast(1)
        val original = sourceAudio?.bitrateKbps?.takeIf { it > 0 }
        val copy = mode.copyAudio && original != null && original <= reserve
        val audio = if (copy) original!! else reserve
        return Budget(minOf(mode.maxBitrateKbps ?: Int.MAX_VALUE, total - audio), audio, copy)
    }
}

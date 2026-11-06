@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MimeTypes
import com.hesier.lumy.core.PlaybackMode
import com.hesier.lumy.core.PlaybackRequest
import com.hesier.lumy.core.PlaybackSource

internal class PlayerMediaItems(
    private val pgsSelection: PgsTrackSelection,
    private val selection: PlaybackSelection,
) {
    fun build(req: PlaybackRequest, source: PlaybackSource, timingAttempt: PlaybackStartupTiming.Attempt?): List<MediaItem> {
        pgsSelection.configure(
            req.version.subtitles.firstOrNull { it.id == req.subtitleStreamId },
            filterTracks = req.mode is PlaybackMode.Direct && !source.isHls &&
                req.version.parts.size == 1 && source.urls.size == 1,
            secondary = selection.secondarySubtitle,
        )
        // Keep multipart playback seamless; external subtitles belong to the first part.
        return source.urls.mapIndexed { i, url ->
            androidx.media3.common.MediaItem.Builder()
                .setUri(url)
                .setMediaId("${req.item.id}#$i")
                .apply {
                    if (timingAttempt != null) {
                        setTag(timingAttempt)
                        if (!source.isHls) setCustomCacheKey(timingAttempt.key)
                    }
                    if (source.isHls) setMimeType(MimeTypes.APPLICATION_M3U8)
                    else if (req.mode is PlaybackMode.Direct &&
                        req.version.container?.lowercase() in setOf("rm", "rmvb")
                    ) setMimeType("application/vnd.rn-realmedia")
                }
                .setSubtitleConfigurations(
                    if (i != 0) emptyList() else source.externalSubtitles.filter {
                        // A missing unselected SUP must not prevent the video from starting.
                        it.codec.lowercase() !in SupMediaSourceFactory.CODECS ||
                            it.streamId == req.subtitleStreamId || it.streamId == selection.secondarySubtitle?.id
                    }.map {
                        SubtitleConfiguration.Builder(Uri.parse(it.url))
                            .setId(EXTERNAL_PREFIX + it.streamId)
                            .setMimeType(subtitleMime(it.codec))
                            .setLanguage(it.language)
                            .setLabel(it.label)
                            .build()
                    },
                )
                .build()
        }
    }

    private fun subtitleMime(codec: String) = when (codec.lowercase()) {
        "sup", "pgs", "hdmv_pgs_subtitle" -> MimeTypes.APPLICATION_PGS
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    private companion object { const val EXTERNAL_PREFIX = "ext:" }
}

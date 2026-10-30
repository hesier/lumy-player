@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory

/** SUP needs timed streaming extraction, not the whole-file text subtitle extractor. */
internal class SupMediaSourceFactory(
    private val delegate: MediaSource.Factory,
    private val dataSources: DataSource.Factory,
) : MediaSource.Factory by delegate {
    companion object {
        val CODECS = setOf("sup", "pgs", "hdmv_pgs_subtitle")
    }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val subtitles = mediaItem.localConfiguration?.subtitleConfigurations.orEmpty()
        val sup = subtitles.filter { it.mimeType == MimeTypes.APPLICATION_PGS }
        if (sup.isEmpty()) return delegate.createMediaSource(mediaItem)
        val main = delegate.createMediaSource(mediaItem.buildUpon()
            .setSubtitleConfigurations(subtitles.filterNot { it in sup }).build())
        val sources = sup.map { subtitle ->
            val format = Format.Builder().setId(subtitle.id).setSampleMimeType(MimeTypes.APPLICATION_PGS)
                .setLanguage(subtitle.language).setLabel(subtitle.label)
                .setSelectionFlags(subtitle.selectionFlags).setRoleFlags(subtitle.roleFlags).build()
            ProgressiveMediaSource.Factory(dataSources, ExtractorsFactory { arrayOf(SupExtractor(format)) })
                .createMediaSource(MediaItem.fromUri(subtitle.uri))
        }
        return MergingMediaSource(false, false, main, *sources.toTypedArray())
    }
}

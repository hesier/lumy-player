@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player.legacy

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.extractor.text.SubtitleParser

/** Route explicitly identified RM media through FFmpeg, retaining the ordinary source pipeline. */
class LegacyRmMediaSourceFactory(
    private val dataSources: DataSource.Factory,
    extractors: ExtractorsFactory,
    private var subtitleParsers: SubtitleParser.Factory,
    private val delegate: MediaSource.Factory,
) : MediaSource.Factory by delegate {
    private val progressive = ProgressiveMediaSource.Factory(
        dataSources, LegacyRmProgressiveExtractor.Factory(dataSources, extractors),
    )
    private var errorPolicy: LoadErrorHandlingPolicy? = null
    private var parseSubtitles = true

    companion object {
        const val MIME_TYPE = "application/vnd.rn-realmedia"
    }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val local = mediaItem.localConfiguration ?: return delegate.createMediaSource(mediaItem)
        val extension = local.uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        val realMedia = local.mimeType == MIME_TYPE || extension == "rm" || extension == "rmvb"
        if (!realMedia || local.imageDurationMs != C.TIME_UNSET || local.adsConfiguration != null) {
            return delegate.createMediaSource(mediaItem)
        }
        val main = progressive.createMediaSource(mediaItem)
        val subtitles = local.subtitleConfigurations.map(::subtitleSource)
        val source = if (subtitles.isEmpty()) main else MergingMediaSource(main, *subtitles.toTypedArray())
        val clip = mediaItem.clippingConfiguration
        if (clip.startPositionUs == 0L && clip.endPositionUs == C.TIME_END_OF_SOURCE &&
            !clip.relativeToDefaultPosition) return source
        return ClippingMediaSource.Builder(source).setStartPositionUs(clip.startPositionUs)
            .setEndPositionUs(clip.endPositionUs).setEnableInitialDiscontinuity(!clip.startsAtKeyFrame)
            .setAllowDynamicClippingUpdates(clip.relativeToLiveWindow)
            .setRelativeToDefaultPosition(clip.relativeToDefaultPosition)
            .setAllowUnseekableMedia(clip.allowUnseekableMedia).build()
    }

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        progressive.setDrmSessionManagerProvider(provider)
        delegate.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        errorPolicy = policy
        progressive.setLoadErrorHandlingPolicy(policy)
        delegate.setLoadErrorHandlingPolicy(policy)
        return this
    }

    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): MediaSource.Factory {
        subtitleParsers = factory
        delegate.setSubtitleParserFactory(factory)
        return this
    }

    @Suppress("DEPRECATION")
    override fun experimentalParseSubtitlesDuringExtraction(parse: Boolean): MediaSource.Factory {
        parseSubtitles = parse
        delegate.experimentalParseSubtitlesDuringExtraction(parse)
        return this
    }

    @Suppress("DEPRECATION")
    private fun subtitleSource(subtitle: MediaItem.SubtitleConfiguration): MediaSource {
        val format = Format.Builder().setId(subtitle.id).setSampleMimeType(subtitle.mimeType)
            .setLanguage(subtitle.language).setLabel(subtitle.label)
            .setSelectionFlags(subtitle.selectionFlags).setRoleFlags(subtitle.roleFlags).build()
        if (parseSubtitles && subtitleParsers.supportsFormat(format)) {
            val factory = ProgressiveMediaSource.Factory(dataSources, ExtractorsFactory {
                arrayOf(SubtitleExtractor(subtitleParsers.create(format), format))
            })
            errorPolicy?.let(factory::setLoadErrorHandlingPolicy)
            return factory.createMediaSource(MediaItem.fromUri(subtitle.uri))
        }
        val factory = SingleSampleMediaSource.Factory(dataSources)
        errorPolicy?.let(factory::setLoadErrorHandlingPolicy)
        return factory.createMediaSource(subtitle, C.TIME_UNSET)
    }
}

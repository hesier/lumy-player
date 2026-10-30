@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.TrackOutput

/** Normalize the decoder input only for an explicitly selected HEVC compatibility attempt. */
class DolbyVisionHevcExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val enabled: () -> Boolean,
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(delegate.createExtractors(uri, responseHeaders))

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        if (!enabled()) extractors else extractors.map(::HevcExtractor).toTypedArray()
}

private class HevcExtractor(private val delegate: Extractor) : Extractor by delegate {
    override fun init(output: ExtractorOutput) = delegate.init(HevcOutput(output))
}

private class HevcOutput(private val delegate: ExtractorOutput) : ExtractorOutput by delegate {
    override fun track(id: Int, type: Int): TrackOutput {
        val output = delegate.track(id, type)
        return if (type == C.TRACK_TYPE_VIDEO) HevcTrackOutput(output) else output
    }
}

private class HevcTrackOutput(private val delegate: TrackOutput) : TrackOutput by delegate {
    override fun format(format: Format) {
        val codecs = format.codecs
        val hevcDolbyVision = format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION &&
            (codecs == null || codecs.startsWith("dvhe.") || codecs.startsWith("dvh1."))
        // Preserve CSD/sample bytes and color metadata; P5 color correction is a separate operation.
        delegate.format(if (hevcDolbyVision) format.buildUpon()
            .setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(null).build() else format)
    }
}

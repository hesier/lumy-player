@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.text.SubtitleParser

internal class PgsParserFactory(private val delegate: SubtitleParser.Factory) : SubtitleParser.Factory by delegate {
    override fun create(format: Format): SubtitleParser =
        if (format.sampleMimeType == MimeTypes.APPLICATION_PGS) MultiObjectPgsParser() else delegate.create(format)
}

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory

/** Both native renderers need their own styles/events; the source is still read only once. */
internal class DualAssParserFactory(
    primary: AssHandler,
    secondary: AssHandler,
    private val secondaryEnabled: () -> Boolean,
) : SubtitleParser.Factory {
    private val primary = AssSubtitleParserFactory(primary)
    private val secondary = AssSubtitleParserFactory(secondary)

    override fun supportsFormat(format: Format) = primary.supportsFormat(format)
    override fun getCueReplacementBehavior(format: Format) = primary.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser {
        val first = primary.create(format)
        if (format.sampleMimeType != MimeTypes.TEXT_SSA || !secondaryEnabled()) return first
        val second = secondary.create(format)
        return object : SubtitleParser by first {
            override fun parse(data: ByteArray, offset: Int, length: Int,
                outputOptions: SubtitleParser.OutputOptions, output: Consumer<CuesWithTiming>) {
                first.parse(data, offset, length, outputOptions, output)
                second.parse(data, offset, length, outputOptions) { }
            }

            override fun reset() { first.reset(); second.reset() }
        }
    }
}

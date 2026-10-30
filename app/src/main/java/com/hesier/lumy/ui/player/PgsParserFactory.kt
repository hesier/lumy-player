@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import com.hesier.lumy.util.DebugLog

/** Each MKV extractor owns its track registry and immutable selection snapshot. */
internal class PgsParserFactory(
    private val delegate: SubtitleParser.Factory,
    private val selection: PgsSelection = PgsSelection.All,
) : SubtitleParser.Factory by delegate {
    private val trackIds = HashSet<String?>()

    override fun create(format: Format): SubtitleParser {
        if (format.sampleMimeType != MimeTypes.APPLICATION_PGS) return delegate.create(format)
        trackIds.add(format.id)
        val parser = MultiObjectPgsParser()
        return object : SubtitleParser by parser {
            private var lastRender: Boolean? = null

            override fun parse(
                data: ByteArray,
                offset: Int,
                length: Int,
                outputOptions: SubtitleParser.OutputOptions,
                output: Consumer<CuesWithTiming>,
            ) {
                // Keep all tracks if the server's index does not match this file's TrackNumber.
                val render = when (selection) {
                    PgsSelection.All -> true
                    PgsSelection.Off -> false
                    is PgsSelection.Track -> selection.id !in trackIds || selection.id == format.id
                }
                if (lastRender != render) {
                    lastRender = render
                    DebugLog.i("LumyPlayer", "pgs track=${format.id} render=$render selection=$selection")
                }
                if (render) parser.parse(data, offset, length, outputOptions, output)
            }
        }
    }
}

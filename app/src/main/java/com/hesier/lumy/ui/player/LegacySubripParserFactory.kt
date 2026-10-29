@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Preserve UTF subtitles; only fall back for legacy Chinese SubRip bytes. */
internal class LegacySubripParserFactory(private val delegate: SubtitleParser.Factory) : SubtitleParser.Factory {
    override fun supportsFormat(format: Format) = delegate.supportsFormat(format)
    override fun getCueReplacementBehavior(format: Format) = delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser {
        val parser = delegate.create(format)
        val language = format.language?.replace('_', '-')?.substringBefore('-')?.lowercase()
        if (format.sampleMimeType != MimeTypes.APPLICATION_SUBRIP || language !in setOf("zh", "zho", "chi")) return parser
        return object : SubtitleParser by parser {
            override fun parse(
                data: ByteArray,
                offset: Int,
                length: Int,
                outputOptions: SubtitleParser.OutputOptions,
                output: Consumer<CuesWithTiming>,
            ) {
                val converted = legacyUtf8(data, offset, length)
                if (converted == null) parser.parse(data, offset, length, outputOptions, output)
                else parser.parse(converted, 0, converted.size, outputOptions, output)
            }
        }
    }

    private fun legacyUtf8(data: ByteArray, offset: Int, length: Int): ByteArray? {
        if (length >= 2) {
            val first = data[offset].toInt() and 0xff
            val second = data[offset + 1].toInt() and 0xff
            if ((first == 0xff && second == 0xfe) || (first == 0xfe && second == 0xff)) return null
        }
        try {
            decode(data, offset, length, Charsets.UTF_8)
            return null
        } catch (_: CharacterCodingException) {
            // A successful strict decode avoids replacing already corrupt bytes with fabricated text.
        }
        return try {
            decode(data, offset, length, Charset.forName("GB18030")).toByteArray(Charsets.UTF_8)
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun decode(data: ByteArray, offset: Int, length: Int, charset: Charset): String =
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data, offset, length)).toString()
}

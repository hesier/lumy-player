@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import com.hesier.lumy.core.SubtitleStream

class PgsParserFactoryTest {
    fun runAll() {
        val selected = factory(PgsSelection.Track("4"))
        val first = selected.create(format("4"))
        val unused = selected.create(format("5"))
        check(output(first).single().cues.size == 1)
        check(output(unused).isEmpty())
        check(output(factory(PgsSelection.Off).create(format("4"))).isEmpty())

        // Numeric TrackNumbers need not be consecutive or match the server's stream index.
        val nonConsecutive = factory(PgsSelection.Track("4"))
        val a = nonConsecutive.create(format("10"))
        val b = nonConsecutive.create(format("20"))
        check(output(a).single().cues.size == 1)
        check(output(b).single().cues.size == 1)
        // A target discovered in another extractor must not disable this file's fallback.
        val independent = factory(PgsSelection.Track("4"))
        check(output(independent.create(format("30"))).size == 1)

        val late = factory(PgsSelection.Track("4"))
        val early = late.create(format("3"))
        check(output(early).size == 1)
        val target = late.create(format("4"))
        check(output(early).isEmpty())
        check(output(target).size == 1)

        testSelectionSnapshots()
        testTextDelegation()
    }

    private fun testSelectionSnapshots() {
        val selection = PgsTrackSelection()
        val subtitle = SubtitleStream(id = "test", index = 3, codec = "pgs", language = "zh",
            title = "PGS", forced = false, selected = true, key = null)
        selection.configure(subtitle, filterTracks = true)
        val before = factory(selection.snapshot)
        selection.configure(null, filterTracks = true)
        check(selection.snapshot == PgsSelection.Off)
        check(output(before.create(format("4"))).size == 1)
        check(output(factory(selection.snapshot).create(format("4"))).isEmpty())

        selection.configure(subtitle.copy(index = null), filterTracks = true)
        check(selection.snapshot == PgsSelection.All)
        selection.configure(null, filterTracks = false)
        check(selection.snapshot == PgsSelection.All)
        selection.configure(subtitle.copy(key = "/external.sup"), filterTracks = true)
        check(selection.snapshot == PgsSelection.Off)
    }

    private fun testTextDelegation() {
        val text = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build()
        val factory = factory(PgsSelection.Off)
        check(factory.supportsFormat(text))
        val bytes = "1\n00:00:01,000 --> 00:00:02,000\nText stays visible\n\n".toByteArray()
        val result = output(factory.create(text), bytes)
        check(result.any { group -> group.cues.any { it.text.toString() == "Text stays visible" } })
    }

    private fun factory(selection: PgsSelection) = PgsParserFactory(DefaultSubtitleParserFactory(), selection)
    private fun format(id: String) = Format.Builder().setId(id).setSampleMimeType(MimeTypes.APPLICATION_PGS).build()

    private fun output(parser: SubtitleParser, bytes: ByteArray = sample()): List<CuesWithTiming> {
        val result = ArrayList<CuesWithTiming>()
        parser.parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues(), Consumer { result.add(it) })
        return result
    }

    private fun sample() =
        segment(0x16, bytes(0, 100, 0, 100, 0x10, 0, 0, 0x80, 0, 0, 1, 0, 1, 0, 0, 0, 20, 0, 30)) +
            segment(0x14, bytes(0, 0, 1, 255, 128, 128, 255)) +
            segment(0x15, bytes(0, 1, 0, 0xc0, 0, 0, 6, 0, 2, 0, 1, 1, 1)) +
            segment(0x80, byteArrayOf())

    private fun segment(type: Int, body: ByteArray) = bytes(type, body.size shr 8, body.size) + body
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}

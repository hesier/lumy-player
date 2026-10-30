@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.content.Context
import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.extractor.DummyTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.hesier.lumy.core.HdrType
import com.hesier.lumy.core.VideoStream

class DolbyVisionCompatibilityTest {
    fun runAll(context: Context) {
        checkInputFormats()
        val queries = mutableListOf<Triple<String, Boolean, Boolean>>()
        val selector = LumyCodecSelector(MediaCodecSelector { mime, secure, tunneling ->
            queries += Triple(mime, secure, tunneling)
            emptyList()
        })
        selector.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false)
        check(queries.last() == Triple(MimeTypes.VIDEO_DOLBY_VISION, false, false)) {
            "Native Dolby Vision must remain the initial decoder route"
        }
        selector.hevcOnly = true
        selector.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, true, true)
        check(queries.last() == Triple(MimeTypes.VIDEO_H265, true, true)) {
            "Compatibility decoding must preserve the decoder requirements"
        }
        selector.getDecoderInfos(MimeTypes.VIDEO_H264, false, true)
        check(queries.last() == Triple(MimeTypes.VIDEO_H264, false, true)) {
            "Dolby Vision fallback must not change other codecs"
        }
        selector.hevcOnly = false
        selector.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false)
        check(queries.last().first == MimeTypes.VIDEO_DOLBY_VISION)

        val device = DeviceProfile(context)
        val hevc = VideoStream(
            codec = "hevc", width = 3840, height = 2160, frameRate = 24f,
            bitDepth = 10, profile = "main 10", hdr = HdrType.NONE, doviProfile = null,
        )
        check(device.videoProblem(hevc) == device.videoProblem(hevc.copy(hdr = HdrType.DOLBY_VISION, doviProfile = 5))) {
            "P5 must be eligible for the same HEVC compatibility attempt, even without a native Dolby decoder"
        }
    }

    private fun checkInputFormats() {
        val csd = byteArrayOf(0, 0, 0, 1, 66)
        val original = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.05.06").setWidth(3840).setHeight(2160).setFrameRate(24f)
            .setInitializationData(listOf(csd)).setLanguage("en").setId("video").build()
        for (enabled in listOf(false, true)) {
            val formats = mutableListOf<Format>()
            val sample = ParsableByteArray(byteArrayOf(1, 2, 3))
            var forwardedData: ParsableByteArray? = null
            val output = object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DummyTrackOutput() {
                    override fun format(format: Format) { formats += format }
                    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                        forwardedData = data
                    }
                }
                override fun endTracks() = Unit
                override fun seekMap(seekMap: SeekMap) = Unit
            }
            val extractor = FormatExtractor(original, sample)
            val factory = DolbyVisionHevcExtractorsFactory(ExtractorsFactory { arrayOf(extractor) }) { enabled }
            factory.createExtractors().single().init(output)
            check(forwardedData === sample) { "Compatibility mode changed compressed video data" }
            val expected = if (enabled) original.buildUpon()
                .setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(null).build() else original
            check(formats.first() == expected) { "Incorrect decoder input format: ${formats.first()}" }
            check(formats.first().initializationData.single() === csd) { "HEVC parameter sets were changed" }
            check(formats[1].sampleMimeType == MimeTypes.AUDIO_E_AC3) { "Audio format was rewritten" }
            check(formats[2].sampleMimeType == MimeTypes.VIDEO_H264) { "Ordinary video format was rewritten" }
        }
    }

    private class FormatExtractor(val format: Format, val sample: ParsableByteArray) : Extractor {
        override fun sniff(input: ExtractorInput) = true
        override fun init(output: ExtractorOutput) {
            val video = output.track(1, C.TRACK_TYPE_VIDEO)
            video.format(format)
            video.sampleData(sample, sample.bytesLeft())
            output.track(2, C.TRACK_TYPE_AUDIO).format(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).build())
            video.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build())
        }
        override fun read(input: ExtractorInput, seekPosition: PositionHolder) = Extractor.RESULT_END_OF_INPUT
        override fun seek(position: Long, timeUs: Long) = Unit
        override fun release() = Unit
    }
}

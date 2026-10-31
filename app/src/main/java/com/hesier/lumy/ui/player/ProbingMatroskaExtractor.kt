@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import com.hesier.lumy.util.DebugLog
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor
import io.github.peerless2012.ass.media.text.AssTrackOutput
import java.io.ByteArrayInputStream

/** Keeps Media3's Cues reader, but gives it only the selected video track's synchronization points. */
class ProbingMatroskaExtractor internal constructor(
    parsers: SubtitleParser.Factory,
    ass: AssHandler,
    internal val pgsSelection: PgsSelection = PgsSelection.All,
    private val secondaryAss: AssHandler? = null,
) : AssMatroskaExtractor(PgsSnapshotParserFactory(parsers), ass, 0) {
    private var attachmentName: String? = null
    private var attachmentMime: String? = null
    private var secondaryWrapped = false
    var timecodeScaleNs = 1_000_000L
        private set
    var firstClusterContentPosition = C.INDEX_UNSET.toLong()
        private set
    internal var chainedCuesPosition: Long? = null
    private val videoCues = VideoCues()

    private fun wrapSecondaryOutput(secondary: AssHandler) {
        val output = OUTPUT_FIELD.get(this) as ExtractorOutput
        OUTPUT_FIELD.set(this, object : ExtractorOutput by output {
            override fun track(id: Int, type: Int): TrackOutput =
                AssTrackOutput(output.track(id, type), secondary, this@ProbingMatroskaExtractor)
        })
        secondaryWrapped = true
    }

    override fun stringElement(id: Int, value: String) {
        if (id == ID_FILE_NAME) attachmentName = value
        if (id == ID_FILE_MIME_TYPE) attachmentMime = value
        super.stringElement(id, value)
    }

    override fun binaryElement(id: Int, contentSize: Int, input: ExtractorInput) {
        if (secondaryAss != null && id == ID_FILE_DATA && attachmentMime in FONT_MIMES) {
            val font = ByteArray(contentSize)
            input.peekFully(font, 0, contentSize)
            input.resetPeekPosition()
            secondaryAss.addFont(requireNotNull(attachmentName), font)
        }
        super.binaryElement(id, contentSize, input)
    }

    override fun getElementType(id: Int): Int =
        super.getElementType(if (id == ID_CUE_TRACK) ID_CUE_TIME else id)

    override fun integerElement(id: Int, value: Long) {
        if (id == ID_TIMECODE_SCALE) timecodeScaleNs = value
        if (!videoCues.integer(id, value)) super.integerElement(id, value)
    }

    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        if (id == ID_EBML && !secondaryWrapped) secondaryAss?.let(::wrapSecondaryOutput)
        if (id == ID_ATTACHED_FILE) { attachmentName = null; attachmentMime = null }
        videoCues.start(id)
        if (id == ID_CLUSTER && firstClusterContentPosition < 0) firstClusterContentPosition = contentPosition
        super.startMasterElement(id, contentPosition, contentSize)
        // SeekPosition is relative to Segment; inject only after the real Segment has initialized it.
        if (id == ID_SEGMENT) chainedCuesPosition?.let(::installCuesPointer)
    }

    override fun endMasterElement(id: Int) {
        if (id == ID_VIDEO) getCurrentTrack(id).let { secondaryAss?.setVideoSize(it.width, it.height) }
        videoCues.end(id)
        if (id == ID_CUE_POINT) videoCues.point()?.let { (time, position) ->
            super.integerElement(ID_CUE_TIME, time)
            super.integerElement(ID_CUE_CLUSTER_POSITION, position)
        }
        super.endMasterElement(id)
    }

    private fun installCuesPointer(relative: Long) {
        val bytes = byteArrayOf(0x1c, 0x53, 0xbb.toByte(), 0x6b)
        val stream = ByteArrayInputStream(bytes)
        val input = DefaultExtractorInput(DataReader { target, offset, size -> stream.read(target, offset, size) }, 0, bytes.size.toLong())
        super.startMasterElement(ID_SEEK, 0, 0)
        super.binaryElement(ID_SEEK_ID, bytes.size, input)
        super.integerElement(ID_SEEK_POSITION, relative)
        super.endMasterElement(ID_SEEK)
        DebugLog.i("LumyPlayer", "mkv chained video Cues relative=$relative")
    }

    private class VideoCues {
        private var trackNumber = -1L
        private var trackType = -1L
        private val videoTracks = HashSet<Long>()
        private var cueTime: Long? = null
        private var cueTrack = -1L
        private var cuePosition = -1L
        private var firstPosition: Long? = null
        private var videoPosition: Long? = null

        fun start(id: Int) {
            when (id) {
                ID_TRACK_ENTRY -> { trackNumber = -1; trackType = -1 }
                ID_CUE_POINT -> { cueTime = null; firstPosition = null; videoPosition = null }
                ID_CUE_TRACK_POSITIONS -> { cueTrack = -1; cuePosition = -1 }
            }
        }

        fun integer(id: Int, value: Long): Boolean {
            when (id) {
                ID_TRACK_NUMBER -> trackNumber = value
                ID_TRACK_TYPE -> trackType = value
                ID_CUE_TIME -> { cueTime = value; return true }
                ID_CUE_TRACK -> { cueTrack = value; return true }
                ID_CUE_CLUSTER_POSITION -> { cuePosition = value; return true }
            }
            return false
        }

        fun end(id: Int) {
            if (id == ID_TRACK_ENTRY && trackType == 1L && trackNumber >= 0) videoTracks.add(trackNumber)
            if (id != ID_CUE_TRACK_POSITIONS || cuePosition < 0) return
            if (firstPosition == null) firstPosition = cuePosition
            if (cueTrack in videoTracks && videoPosition == null) videoPosition = cuePosition
        }

        fun point(): Pair<Long, Long>? {
            val time = cueTime?.takeIf { it >= 0 } ?: return null
            // Preserve the original indexing for audio-only or ambiguous multi-video containers.
            val position = if (videoTracks.size == 1) videoPosition else firstPosition
            return position?.let { time to it }
        }
    }

    companion object {
        private val OUTPUT_FIELD = androidx.media3.extractor.mkv.MatroskaExtractor::class.java
            .getDeclaredField("extractorOutput").apply { isAccessible = true }
        private val FONT_MIMES = setOf("font/ttf", "font/otf", "font/sfnt", "font/woff", "font/woff2",
            "application/font-sfnt", "application/font-woff", "application/x-truetype-font",
            "application/vnd.ms-opentype", "application/x-font-ttf")
        const val ID_CLUSTER = 0x1F43B675
        const val ID_TIMECODE_SCALE = 0x2AD7B1
        private const val ID_SEGMENT = 0x18538067
        private const val ID_SEEK = 0x4DBB
        private const val ID_SEEK_ID = 0x53AB
        private const val ID_SEEK_POSITION = 0x53AC
        private const val ID_TRACK_ENTRY = 0xAE
        private const val ID_TRACK_NUMBER = 0xD7
        private const val ID_TRACK_TYPE = 0x83
        private const val ID_CUE_POINT = 0xBB
        private const val ID_CUE_TIME = 0xB3
        private const val ID_CUE_TRACK_POSITIONS = 0xB7
        private const val ID_CUE_TRACK = 0xF7
        private const val ID_CUE_CLUSTER_POSITION = 0xF1
    }
}

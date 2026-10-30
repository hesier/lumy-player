package com.hesier.lumy.ui.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.hesier.lumy.core.VideoStream

/** A ceiling for software attempts, not a promise of real-time playback on every device. */
object SoftwareVideoPolicy {
    const val RV40_MIME = "video/realvideo"
    const val COOK_MIME = "audio/cook"
    private const val MAX_LONG_EDGE = 1920
    private const val MAX_SHORT_EDGE = 1080

    fun withinLimit(width: Int?, height: Int?): Boolean = width != null && height != null &&
        width > 0 && height > 0 && maxOf(width, height) <= MAX_LONG_EDGE &&
        minOf(width, height) <= MAX_SHORT_EDGE

    fun supports(video: VideoStream): Boolean =
        video.codec.lowercase() in setOf("h264", "avc", "avc1", "rv40") &&
            withinLimit(video.width, video.height) && video.hdr == com.hesier.lumy.core.HdrType.NONE

    fun supports(format: Format): Boolean =
        (format.sampleMimeType == MimeTypes.VIDEO_H264 ||
            (format.sampleMimeType == RV40_MIME && format.codecs.equals("rv40", ignoreCase = true))) &&
            withinLimit(format.width, format.height) &&
            format.colorInfo?.colorTransfer != androidx.media3.common.C.COLOR_TRANSFER_ST2084 &&
            format.colorInfo?.colorTransfer != androidx.media3.common.C.COLOR_TRANSFER_HLG
}

package com.hesier.lumy.ui.player.legacy

import androidx.annotation.Keep
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderOutputBuffer
import androidx.media3.decoder.VideoDecoderOutputBuffer
import java.nio.ByteBuffer
import kotlin.math.roundToInt

@Keep
@UnstableApi
internal class SoftwareVideoOutputBuffer(owner: DecoderOutputBuffer.Owner<VideoDecoderOutputBuffer>) :
    VideoDecoderOutputBuffer(owner) {
    var frameWidth = 0
        private set
    var frameHeight = 0
        private set

    // DecoderVideoRenderer 1.8 reports square pixels; expose the display width to preserve SAR.
    fun initRgba(timeUs: Long, width: Int, height: Int, pixelRatio: Float): ByteBuffer {
        require(SoftwareVideoRenderer.isSupportedSize(width, height))
        init(timeUs, C.VIDEO_OUTPUT_MODE_SURFACE_YUV, null)
        frameWidth = width
        frameHeight = height
        this.width = (width * pixelRatio).roundToInt().coerceIn(1, MAX_DISPLAY_WIDTH)
        this.height = height
        val size = width * height * RGBA_BYTES_PER_PIXEL
        var buffer = data
        if (buffer == null || buffer.capacity() < size) {
            buffer = ByteBuffer.allocateDirect(size)
            data = buffer
        }
        buffer.clear()
        buffer.limit(size)
        return buffer
    }

    companion object {
        private const val RGBA_BYTES_PER_PIXEL = 4
        private const val MAX_DISPLAY_WIDTH = 32768
    }
}

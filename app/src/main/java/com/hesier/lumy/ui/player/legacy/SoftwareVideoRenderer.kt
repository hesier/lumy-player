@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player.legacy

import android.os.Handler
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.video.DecoderVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.hesier.lumy.ui.player.SoftwareVideoPolicy

/** CPU decoding is deliberately restricted to the legacy codecs and Full HD sizes we test. */
class SoftwareVideoRenderer(
    allowedJoiningTimeMs: Long,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
) : DecoderVideoRenderer(allowedJoiningTimeMs, eventHandler, eventListener, 50) {
    private var surfaceRenderer = 0L
    private var renderingSurface: Surface? = null

    override fun getName() = "LumySoftwareVideoRenderer"

    override fun supportsFormat(format: Format): Int {
        if (codecName(format) == null) return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        if (format.drmInitData != null) return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM)
        if (!SoftwareVideoPolicy.supports(format)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }
        if (!NativeVideoDecoder.isAvailable()) return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        return RendererCapabilities.create(C.FORMAT_HANDLED, RendererCapabilities.ADAPTIVE_NOT_SEAMLESS, RendererCapabilities.TUNNELING_NOT_SUPPORTED)
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?):
        Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, SoftwareVideoDecoderException> {
        if (cryptoConfig != null || format.drmInitData != null) {
            throw SoftwareVideoDecoderException("Encrypted video is unsupported")
        }
        return SoftwareVideoDecoder(format)
    }

    override fun setDecoderOutputMode(outputMode: Int) {
        require(outputMode != C.VIDEO_OUTPUT_MODE_YUV) { "Software video requires a Surface" }
    }

    override fun renderOutputBufferToSurface(outputBuffer: VideoDecoderOutputBuffer, surface: Surface) {
        try {
            val buffer = outputBuffer as SoftwareVideoOutputBuffer
            if (surfaceRenderer == 0L || renderingSurface !== surface) {
                releaseSurfaceRenderer()
                surfaceRenderer = NativeVideoDecoder.createRenderer(surface)
                renderingSurface = surface
                if (surfaceRenderer == 0L) throw SoftwareVideoDecoderException("EGL video initialization failed")
            }
            val result = NativeVideoDecoder.render(surfaceRenderer, checkNotNull(buffer.data),
                buffer.frameWidth, buffer.frameHeight)
            if (result < 0) throw SoftwareVideoDecoderException(
                "Software video surface: ${NativeVideoDecoder.errorString(result)}")
        } catch (error: RuntimeException) {
            throw SoftwareVideoDecoderException("EGL video rendering failed", error)
        } finally {
            outputBuffer.release()
        }
    }

    override fun handleMessage(messageType: Int, message: Any?) {
        if (messageType == Renderer.MSG_SET_VIDEO_OUTPUT && message !== renderingSurface) {
            releaseSurfaceRenderer()
        }
        super.handleMessage(messageType, message)
    }

    override fun onStopped() {
        releaseSurfaceRenderer()
        super.onStopped()
    }

    override fun onDisabled() {
        releaseSurfaceRenderer()
        super.onDisabled()
    }

    private fun releaseSurfaceRenderer() {
        if (surfaceRenderer != 0L) NativeVideoDecoder.releaseRenderer(surfaceRenderer)
        surfaceRenderer = 0L
        renderingSurface = null
    }

    companion object {
        const val RV40_MIME_TYPE = SoftwareVideoPolicy.RV40_MIME

        internal fun codecName(format: Format): String? = when (format.sampleMimeType) {
            MimeTypes.VIDEO_H264 -> "h264"
            RV40_MIME_TYPE -> if (format.codecs.equals("rv40", ignoreCase = true)) "rv40" else null
            else -> null
        }

        internal fun isSupportedSize(width: Int, height: Int): Boolean =
            SoftwareVideoPolicy.withinLimit(width, height)

        internal fun isHdr(format: Format): Boolean = format.colorInfo?.colorTransfer.let {
            it == C.COLOR_TRANSFER_ST2084 || it == C.COLOR_TRANSFER_HLG
        }
    }
}

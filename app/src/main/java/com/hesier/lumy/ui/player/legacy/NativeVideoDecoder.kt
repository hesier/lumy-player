package com.hesier.lumy.ui.player.legacy

import android.view.Surface
import androidx.annotation.Keep
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import java.nio.ByteBuffer

/** Shares the packaged FFmpeg library with the audio extension. */
@Keep
@UnstableApi
internal object NativeVideoDecoder {
    fun isAvailable(): Boolean = try {
        FfmpegLibrary.isAvailable() && nativeIsAvailable()
    } catch (_: LinkageError) {
        false
    }

    external fun nativeIsAvailable(): Boolean
    external fun create(codec: String, extraData: ByteArray, width: Int, height: Int,
                        pixelRatio: Float, colorSpace: Int, colorRange: Int): Long
    external fun send(context: Long, data: ByteBuffer?, offset: Int, size: Int,
                      timeUs: Long, endOfStream: Boolean): Int
    external fun receive(context: Long, output: SoftwareVideoOutputBuffer): Int
    external fun flush(context: Long)
    external fun close(context: Long)
    external fun createRenderer(surface: Surface): Long
    external fun render(renderer: Long, data: ByteBuffer, width: Int, height: Int): Int
    external fun releaseRenderer(renderer: Long)
    external fun errorString(error: Int): String
}

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player.legacy

import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.RendererCapabilities

/** Generated five-frame High10 fixture includes two B-frames and a non-square pixel ratio. */
class SoftwareVideoDecoderTest {
    fun runAll() {
        check(NativeVideoDecoder.isAvailable()) { "Software video JNI is unavailable" }
        capabilities()
        val decoder = SoftwareVideoDecoder(format())
        val reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)
        try {
            val first = decode(decoder, reader)
            check(first == listOf(0L, 200_000L, 400_000L, 600_000L, 800_000L)) { "Missing delayed frames: $first" }
            decoder.flush()
            decoder.setOutputStartTimeUs(400_000)
            val afterSeek = decode(decoder, null)
            check(afterSeek == listOf(400_000L, 600_000L, 800_000L)) { "Incorrect seek timestamps: $afterSeek" }
            decoder.flush()
            decoder.setOutputStartTimeUs(0)
            // Discard input while the worker may be holding it, then replay a fresh keyframe.
            queueFirstPacket(decoder)
            decoder.flush()
            val afterFlush = decode(decoder, null)
            check(first == afterFlush) { "Flush retained stale frames: $afterFlush" }
        } finally {
            decoder.release()
            reader.close()
        }
    }

    private fun capabilities() {
        val renderer = SoftwareVideoRenderer(0, null, null)
        check(RendererCapabilities.getFormatSupport(renderer.supportsFormat(format())) == C.FORMAT_HANDLED)
        for ((width, height) in listOf(-1 to -1, 3840 to 2160, 1920 to 1088, 2048 to 800)) {
            val candidate = format().buildUpon().setWidth(width).setHeight(height).build()
            check(RendererCapabilities.getFormatSupport(renderer.supportsFormat(candidate)) != C.FORMAT_HANDLED)
        }
        val hevc = format().buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).build()
        check(RendererCapabilities.getFormatSupport(renderer.supportsFormat(hevc)) == C.FORMAT_UNSUPPORTED_TYPE)
        val real = format().buildUpon().setSampleMimeType(SoftwareVideoRenderer.RV40_MIME_TYPE)
        check(RendererCapabilities.getFormatSupport(renderer.supportsFormat(real.setCodecs("rv30").build())) == C.FORMAT_UNSUPPORTED_TYPE)
        check(RendererCapabilities.getFormatSupport(renderer.supportsFormat(real.setCodecs("rv40").build())) == C.FORMAT_HANDLED)
    }

    private fun decode(decoder: SoftwareVideoDecoder, reader: ImageReader?): List<Long> {
        val packets = packets()
        val result = mutableListOf<Long>()
        var inputIndex = 0
        var endOfStream = false
        var rendered = false
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (!endOfStream && SystemClock.elapsedRealtime() < deadline) {
            if (inputIndex <= packets.size) {
                decoder.dequeueInputBuffer()?.let { input ->
                    if (inputIndex == packets.size) input.addFlag(C.BUFFER_FLAG_END_OF_STREAM) else {
                        val packet = packets[inputIndex]
                        input.ensureSpaceForWrite(packet.size)
                        checkNotNull(input.data).put(packet)
                        input.timeUs = PACKET_TIMES[inputIndex]
                        input.flip()
                    }
                    decoder.queueInputBuffer(input)
                    inputIndex++
                }
            }
            val output = decoder.dequeueOutputBuffer()
            if (output == null) {
                Thread.sleep(1)
                continue
            }
            try {
                endOfStream = output.isEndOfStream
                if (!endOfStream) {
                    val frame = output as SoftwareVideoOutputBuffer
                    check(frame.width == WIDTH * 2 && frame.height == HEIGHT) { "Lost SAR: ${frame.width}x${frame.height}" }
                    val rgba = checkNotNull(frame.data)
                    check(rgba.remaining() == WIDTH * HEIGHT * 4)
                    check((rgba.get(3).toInt() and 255) == 255) { "Invalid RGBA alpha" }
                    result.add(frame.timeUs)
                    if (reader != null && !rendered) {
                        val renderer = NativeVideoDecoder.createRenderer(reader.surface)
                        check(renderer != 0L)
                        try {
                            check(NativeVideoDecoder.render(renderer, rgba, frame.frameWidth, frame.frameHeight) == 0)
                            awaitImage(reader)
                        } finally {
                            NativeVideoDecoder.releaseRenderer(renderer)
                        }
                        configureHardwareOnSameSurface(reader)
                        rendered = true
                    }
                }
            } finally {
                output.release()
            }
        }
        check(endOfStream) { "Software video did not drain EOS" }
        return result
    }

    private fun configureHardwareOnSameSurface(reader: ImageReader) {
        // CPU lock/post left the producer connected after renderer release and failed here.
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            codec.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 128, 128),
                reader.surface, null, 0)
        } finally {
            codec.release()
        }
    }

    private fun awaitImage(reader: ImageReader) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            reader.acquireLatestImage()?.let { image ->
                image.use {
                    check(it.width == WIDTH && it.height == HEIGHT)
                    check((it.planes[0].buffer.get(3).toInt() and 255) == 255)
                }
                return
            }
            Thread.sleep(1)
        }
        error("Surface did not receive the decoded High10 frame")
    }

    private fun queueFirstPacket(decoder: SoftwareVideoDecoder) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var input: DecoderInputBuffer? = null
        while (input == null && SystemClock.elapsedRealtime() < deadline) {
            input = decoder.dequeueInputBuffer()
            if (input == null) Thread.sleep(1)
        }
        val buffer = checkNotNull(input)
        val first = packets().first()
        buffer.ensureSpaceForWrite(first.size)
        checkNotNull(buffer.data).put(first)
        buffer.timeUs = 0
        buffer.flip()
        decoder.queueInputBuffer(buffer)
    }

    private fun format() = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
        .setCodecs("avc1.6E0010").setWidth(WIDTH).setHeight(HEIGHT).build()

    private fun packets(): List<ByteArray> {
        val data = Base64.decode(HIGH10, Base64.DEFAULT)
        var offset = 0
        return PACKET_SIZES.map { size -> data.copyOfRange(offset, offset + size).also { offset += size } }
    }

    companion object {
        private const val WIDTH = 32
        private const val HEIGHT = 24
        private const val TIMEOUT_MS = 10_000L
        private val PACKET_TIMES = longArrayOf(0, 600_000, 200_000, 400_000, 800_000)
        private val PACKET_SIZES = intArrayOf(1186, 91, 18, 48, 42)
        // ffmpeg testsrc2, libx264 High10, five frames, setsar=2, bframes=2, keyint=5.
        private const val HIGH10 = "AAAAAQkQAAAAAWduAAqmzZSX5cQEAAADAAQAAAMAKDxIllgAAAABaOvjyyLAAAABBgX//6XcRem95tlIt5Ys2CDZI+7veDI2NCAtIGNvcmUgMTY0IHIzMTA4IDMxZTE5ZjkgLSBILjI2NC9NUEVHLTQgQVZDIGNvZGVjIC0gQ29weWxlZnQgMjAwMy0yMDIzIC0gaHR0cDovL3d3dy52aWRlb2xhbi5vcmcveDI2NC5odG1sIC0gb3B0aW9uczogY2FiYWM9MSByZWY9MyBkZWJsb2NrPTE6MDowIGFuYWx5c2U9MHgzOjB4MTEzIG1lPWhleCBzdWJtZT03IHBzeT0xIHBzeV9yZD0xLjAwOjAuMDAgbWl4ZWRfcmVmPTEgbWVfcmFuZ2U9MTYgY2hyb21hX21lPTEgdHJlbGxpcz0xIDh4OGRjdD0xIGNxbT0wIGRlYWR6b25lPTIxLDExIGZhc3RfcHNraXA9MSBjaHJvbWFfcXBfb2Zmc2V0PS0yIHRocmVhZHM9MSBsb29rYWhlYWRfdGhyZWFkcz0xIHNsaWNlZF90aHJlYWRzPTAgbnI9MCBkZWNpbWF0ZT0xIGludGVybGFjZWQ9MCBibHVyYXlfY29tcGF0PTAgY29uc3RyYWluZWRfaW50cmE9MCBiZnJhbWVzPTIgYl9weXJhbWlkPTIgYl9hZGFwdD0xIGJfYmlhcz0wIGRpcmVjdD0xIHdlaWdodGI9MSBvcGVuX2dvcD0wIHdlaWdodHA9MiBrZXlpbnQ9NSBrZXlpbnRfbWluPTMgc2NlbmVjdXQ9MCBpbnRyYV9yZWZyZXNoPTAgcmNfbG9va2FoZWFkPTUgcmM9Y3JmIG1idHJlZT0xIGNyZj0yMy4wIHFjb21wPTAuNjAgcXBtaW49MCBxcG1heD04MSBxcHN0ZXA9NCBpcF9yYXRpbz0xLjQwIGFxPTE6MS4wMACAAAABZYiEAX/UV2xH13ejSpNLz+O9yOTlmr1UCb4IUAYGeSEzE07vaUKCRt13HUVI64f5uo6/YF4m31VLhxnDXCNSJOMRjdAP2mpPWyJCxqGPjJ4B8uBMIdc7k1BUCRl8kSjrIjcgCLQuMqhN4rDs+zvZQmA2mO/fMu8v+sM2uL6np+yZndilLAkqw5GLojv7TwIBuOjgVnlkhJLcmLvnnpoLuKfzafakkzFCt+VLriig69yQqKb5xqjKgILX3IlvTPHdnuMz8AhtKr0rtxrKf3jRKhUuhapsoDFltmrppD39UuKUnKeMh3pt9jryP1M82nKx6KJ23a0JqRCH3LBOFJzlFaLOztdzllgFCLlj2JotYTYCmcYAQgFJGm5E76E2IGoQW4Alsuv+4AvTn0cz8GVzuMBpcxObvphN8FSDr2dDAMMOlyugyTeit7huvN4lxJsEC0pA4islbCYga63WddhGejsEfdHqWVuVFkItdSxK0pb/PSmaoAYFvxlAGvYJ6QWHknJifh4ZXJC0rLp96VrP/XHj32QtdmpGboTVAAsi6JEjq1AT2k0DkseGECKQl1XVnSdOsjFH+qnGTF6ziXJsbqoZYBOs+wAAAAEJMAAAAUGaI2xN/8EKUVL1KwnZ5TdbNmZb3l9a08vNm/gL06PX+/Mu0Jv/ipWGP/f7LLFxgF50K4gNI7YgS9CutNHYJo/9KKiKRqP1ZFZSBP6KQOPtlIYAAAABCVAAAAFBnkF4iv/z6H0AAAABCVAAAAEBnmJqRX/o7RL8uSOpR0uBdC4KIlaLLcNXsKP7hbVvjPneDBFin+AAAAABCTAAAAFBmmQ0tEwEV7wRdOqeodiU/qrYzj4CmclHgSVoSY/UsHE="
    }
}

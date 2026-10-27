@file:OptIn(UnstableApi::class)

package com.hesier.lumy.ui.player

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.hesier.lumy.core.HdrType
import com.hesier.lumy.core.SubtitleStream
import com.hesier.lumy.core.VideoStream

/**
 * 查询本机解码能力，决定能否直接播放。结果按 mime 缓存，MediaCodecList 查询在低端设备上不便宜。
 */
class DeviceProfile(context: Context) {

    private val decoderCache = HashMap<String, List<MediaCodecInfo>>()

    val displaySupportsHdr: Boolean = run {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@run false
        @Suppress("DEPRECATION")
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        display.hdrCapabilities?.supportedHdrTypes?.any {
            it == Display.HdrCapabilities.HDR_TYPE_HDR10 || it == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
        } == true
    }

    /** @return null 表示可以硬解；否则是不能直接播放的原因 */
    fun videoProblem(v: VideoStream): PlaybackIssue? {
        val mime = videoMime(v.codec) ?: return PlaybackIssue.UnknownVideoCodec(v.codec)
        // 杜比视界 P5 没有 HDR10 兼容层，没有杜比视界解码器时颜色会完全错误
        if (v.hdr == HdrType.DOLBY_VISION && v.doviProfile == 5 && !hasDolbyVisionDecoder()) {
            return PlaybackIssue.DolbyVisionProfile5
        }
        val candidates = decoders(mime)
        if (candidates.isEmpty()) return PlaybackIssue.NoVideoDecoder(v.codec)
        val tenBit = (v.bitDepth ?: 8) > 8
        val ok = candidates.any { info ->
            val sizeOk = v.width == null || v.height == null ||
                info.isVideoSizeAndRateSupportedV21(v.width!!, v.height!!, (v.frameRate ?: 30f).toDouble())
            sizeOk && (!tenBit || supportsTenBit(info, mime))
        }
        if (ok) return null
        val res = listOfNotNull(v.width, v.height).joinToString("x")
        return PlaybackIssue.VideoTooDemanding(res, tenBit, v.codec)
    }

    /** 优先硬件解码，其次 FFmpeg 扩展（DTS、TrueHD 等） */
    fun audioProblem(codec: String): PlaybackIssue? {
        val mime = audioMime(codec) ?: return PlaybackIssue.UnknownAudioCodec(codec)
        if (decoders(mime).isNotEmpty()) return null
        if (FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mime)) return null
        return PlaybackIssue.NoAudioDecoder(codec)
    }

    fun containerSupported(container: String?): Boolean =
        container == null || container.lowercase() in SUPPORTED_CONTAINERS

    /** 客户端能否自己渲染这条字幕（不能的话需要服务器烧录进画面） */
    fun canRenderSubtitle(s: SubtitleStream, transcoding: Boolean): Boolean {
        val codec = s.codec.lowercase()
        return if (s.external) {
            codec in EXTERNAL_TEXT_SUBTITLES
        } else {
            // 转码输出的 HLS 不带内封字幕，只能烧录
            !transcoding && codec in EMBEDDED_SUBTITLES
        }
    }

    fun hasDolbyVisionDecoder(): Boolean = decoders(MimeTypes.VIDEO_DOLBY_VISION).isNotEmpty()

    fun decoderSummary(mime: String): String = decoders(mime).joinToString { it.name }

    /** 本机信息页用：有没有解码器、是否硬解、是否支持 10-bit */
    fun hasDecoder(mime: String): Boolean = decoders(mime).isNotEmpty()

    fun hasHardwareDecoder(mime: String): Boolean = decoders(mime).any { it.hardwareAccelerated }

    fun supportsTenBit(mime: String): Boolean = decoders(mime).any { supportsTenBit(it, mime) }

    private fun decoders(mime: String): List<MediaCodecInfo> = decoderCache.getOrPut(mime) {
        try {
            MediaCodecUtil.getDecoderInfos(mime, false, false)
        } catch (e: MediaCodecUtil.DecoderQueryException) {
            emptyList()
        }
    }

    private fun supportsTenBit(info: MediaCodecInfo, mime: String): Boolean {
        val levels = info.profileLevels
        return when (mime) {
            MimeTypes.VIDEO_H265 -> levels.any {
                it.profile == CodecProfileLevel.HEVCProfileMain10 || it.profile == CodecProfileLevel.HEVCProfileMain10HDR10
            }
            MimeTypes.VIDEO_VP9 -> levels.any { it.profile == CodecProfileLevel.VP9Profile2 || it.profile == CodecProfileLevel.VP9Profile2HDR }
            MimeTypes.VIDEO_AV1 -> levels.any { it.profile == CodecProfileLevel.AV1ProfileMain10 || it.profile == CodecProfileLevel.AV1ProfileMain10HDR10 }
            // H.264 Hi10P 基本没有硬件支持
            else -> false
        }
    }

    companion object {
        private val SUPPORTED_CONTAINERS = setOf(
            "mkv", "matroska", "mp4", "m4v", "mov", "webm", "ts", "mpegts", "flv", "avi", "ogg", "3gp", "mp3", "aac", "flac", "m4a",
        )
        private val EXTERNAL_TEXT_SUBTITLES = setOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt")
        private val EMBEDDED_SUBTITLES = EXTERNAL_TEXT_SUBTITLES + setOf(
            "pgs", "hdmv_pgs_subtitle", "dvd_subtitle", "vobsub", "dvb_subtitle", "mov_text", "tx3g",
        )

        fun videoMime(codec: String): String? = when (codec.lowercase()) {
            "h264", "avc", "avc1" -> MimeTypes.VIDEO_H264
            "hevc", "h265", "hvc1", "hev1" -> MimeTypes.VIDEO_H265
            "vp9" -> MimeTypes.VIDEO_VP9
            "vp8" -> MimeTypes.VIDEO_VP8
            "av1" -> MimeTypes.VIDEO_AV1
            "mpeg2video", "mpeg2" -> MimeTypes.VIDEO_MPEG2
            "mpeg4" -> MimeTypes.VIDEO_MP4V
            "vc1" -> "video/wvc1"
            else -> null
        }

        fun audioMime(codec: String): String? = when (codec.lowercase()) {
            "aac" -> MimeTypes.AUDIO_AAC
            "ac3" -> MimeTypes.AUDIO_AC3
            "eac3" -> MimeTypes.AUDIO_E_AC3
            "dca", "dts" -> MimeTypes.AUDIO_DTS
            "dts-hd", "dtshd" -> MimeTypes.AUDIO_DTS_HD
            "truehd" -> MimeTypes.AUDIO_TRUEHD
            "flac" -> MimeTypes.AUDIO_FLAC
            "mp3" -> MimeTypes.AUDIO_MPEG
            "mp2" -> MimeTypes.AUDIO_MPEG_L2
            "opus" -> MimeTypes.AUDIO_OPUS
            "vorbis" -> MimeTypes.AUDIO_VORBIS
            "alac" -> MimeTypes.AUDIO_ALAC
            else -> if (codec.startsWith("pcm")) MimeTypes.AUDIO_RAW else null
        }
    }
}

package com.hesier.lumy.ui.player

import android.content.Context
import com.hesier.lumy.core.AudioStream
import com.hesier.lumy.core.HdrType
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.PlaybackMode
import com.hesier.lumy.core.SubtitleStream
import com.hesier.lumy.R

/** 用户在“画质”菜单里的选择 */
sealed interface Quality {
    data object Original : Quality
    data class Limited(val height: Int, val bitrateKbps: Int) : Quality

    companion object {
        val presets: List<Quality> = listOf(
            Original,
            Limited(1080, 20_000),
            Limited(1080, 12_000),
            Limited(720, 4_000),
            Limited(480, 2_000),
        )
    }
}

fun qualityLabel(context: Context, q: Quality): String = when (q) {
    Quality.Original -> context.getString(R.string.quality_original)
    is Quality.Limited -> context.getString(R.string.quality_transcode, "${q.height}p", "%.0f".format(q.bitrateKbps / 1000f))
}

/** 不能直接播放的原因，显示时再按当前语言转成文字 */
sealed interface PlaybackIssue {
    data object VideoDecodeFailed : PlaybackIssue
    data object DirectPlayFailed : PlaybackIssue
    data class Container(val container: String) : PlaybackIssue
    data class UnknownVideoCodec(val codec: String) : PlaybackIssue
    data object DolbyVisionProfile5 : PlaybackIssue
    data class NoVideoDecoder(val codec: String) : PlaybackIssue
    data class VideoTooDemanding(val resolution: String, val tenBit: Boolean, val codec: String) : PlaybackIssue
    data class UnknownAudioCodec(val codec: String) : PlaybackIssue
    data class NoAudioDecoder(val codec: String) : PlaybackIssue
    data object HdrUnsupported : PlaybackIssue
    data class SubtitleNeedsBurn(val codec: String) : PlaybackIssue
}

fun PlaybackIssue.text(context: Context): String = when (this) {
    PlaybackIssue.VideoDecodeFailed -> context.getString(R.string.issue_video_decode_failed)
    PlaybackIssue.DirectPlayFailed -> context.getString(R.string.issue_direct_play_failed)
    is PlaybackIssue.Container -> context.getString(R.string.issue_container, container.uppercase())
    is PlaybackIssue.UnknownVideoCodec -> context.getString(R.string.issue_unknown_video_codec, codec.uppercase())
    PlaybackIssue.DolbyVisionProfile5 -> context.getString(R.string.issue_dovi_profile5)
    is PlaybackIssue.NoVideoDecoder -> context.getString(R.string.issue_no_video_decoder, codec.uppercase())
    is PlaybackIssue.VideoTooDemanding -> context.getString(
        if (tenBit) R.string.issue_video_too_demanding_10bit else R.string.issue_video_too_demanding,
        resolution,
        codec.uppercase(),
    )
    is PlaybackIssue.UnknownAudioCodec -> context.getString(R.string.issue_unknown_audio_codec, codec.uppercase())
    is PlaybackIssue.NoAudioDecoder -> context.getString(R.string.issue_no_audio_decoder, codec.uppercase())
    PlaybackIssue.HdrUnsupported -> context.getString(R.string.issue_hdr_unsupported)
    is PlaybackIssue.SubtitleNeedsBurn -> context.getString(R.string.issue_subtitle_needs_burn, codec.uppercase())
}

fun List<PlaybackIssue>.text(context: Context): String =
    joinToString(context.getString(R.string.list_separator)) { it.text(context) }

data class Plan(
    val mode: PlaybackMode,
    /** 不能直接播放的原因（禁止转码时是“可能播放不了”的提示） */
    val reasons: List<PlaybackIssue>,
    /** 选中的字幕在这种播放方式下显示不了（需要服务器烧录但没有允许） */
    val subtitleUnavailable: Boolean = false,
)

data class PlanPolicy(
    val allowTranscode: Boolean,
    val allowSubtitleBurn: Boolean,
    /** 用户设置的“电视支持 HDR”；不支持时 HDR 片源让服务器转成 SDR */
    val displaySupportsHdr: Boolean = true,
    /** 服务器转码时能带上内封字幕（[com.hesier.lumy.core.MediaServer.embedsSubtitles]） */
    val serverEmbedsSubtitles: Boolean = false,
)

/**
 * 决定播放方式。优先直接播放：画质无损、服务器零负担，字幕和音轨都在本机处理
 * （ASS 特效字幕由 libass 渲染，PGS 等图形字幕由播放器渲染）。
 * 只有视频/封装/音频本机确实处理不了时才让服务器转码；服务器烧录字幕默认不允许。
 * 电视不支持 HDR 时，HDR 片源也要转码成 SDR：直接播放时盒子往往不做色调映射，画面发灰。
 */
class PlaybackPlanner(private val device: DeviceProfile) {

    fun plan(
        version: MediaVersion,
        audio: AudioStream?,
        subtitle: SubtitleStream?,
        quality: Quality,
        policy: PlanPolicy,
        forceTranscode: Boolean = false,
        /** 本机实际解码视频失败（设备声明支持也不算数），转码时必须重新编码视频 */
        videoDecodeFailed: Boolean = false,
    ): Plan {
        val mediaProblems = buildList {
            if (videoDecodeFailed) add(PlaybackIssue.VideoDecodeFailed) else if (forceTranscode) add(PlaybackIssue.DirectPlayFailed)
            if (!device.containerSupported(version.container)) add(PlaybackIssue.Container(version.container.orEmpty()))
            version.video?.let { device.videoProblem(it) }?.let(::add)
            audio?.let { device.audioProblem(it.codec) }?.let(::add)
        }
        val directSubtitleOk = subtitle == null || device.canRenderSubtitle(subtitle, transcoding = false)

        if (!policy.allowTranscode) {
            return Plan(PlaybackMode.Direct, mediaProblems, subtitleUnavailable = !directSubtitleOk)
        }

        val toSdr = !policy.displaySupportsHdr && (version.video?.hdr ?: HdrType.NONE) != HdrType.NONE
        val wantTranscode = quality is Quality.Limited || mediaProblems.isNotEmpty() || toSdr ||
            // 本机渲染不了的字幕只有允许烧录时才为它转码
            (!directSubtitleOk && policy.allowSubtitleBurn)
        if (!wantTranscode) {
            return Plan(PlaybackMode.Direct, emptyList(), subtitleUnavailable = !directSubtitleOk)
        }

        val videoOk = !videoDecodeFailed && version.video?.let(device::videoProblem) == null
        val reencode = quality is Quality.Limited || !videoOk || toSdr
        val audioOk = audio == null || device.audioProblem(audio.codec) == null
        // 转码输出里没有内封字幕：服务器能带上时由本机渲染，否则要么烧录，要么这条字幕不可用。
        // 视频反正要重新编码时，烧录几乎不增加服务器负担，不受开关限制（Plex 此时也会自己烧录服务器记住的字幕）
        val needsBurn = subtitle != null && !device.canRenderSubtitle(subtitle, transcoding = true)
        val embedId = subtitle?.id?.takeIf { needsBurn && canEmbed(subtitle, policy) }
        val burnId = subtitle?.id?.takeIf { needsBurn && embedId == null && (policy.allowSubtitleBurn || reencode) }
        val mode = when (quality) {
            is Quality.Limited -> PlaybackMode.Transcode(
                quality.bitrateKbps, quality.height, burnSubtitleId = burnId, embedSubtitleId = embedId, copyVideo = false, sdr = toSdr,
                copyAudio = audioOk,
            )
            // 本机解不了的视频要重新编码；超过 1080p 的降到 1080p，解不了 4K HEVC 的设备多半也解不了 4K H.264。
            // 转 SDR 也降到 1080p：4K 色调映射加重新编码对服务器负担很大，不少盒子也解不了 4K H.264
            Quality.Original -> PlaybackMode.Transcode(
                maxHeight = if ((!videoOk || toSdr) && (version.video?.height ?: 0) > 1080) 1080 else null,
                burnSubtitleId = burnId,
                embedSubtitleId = embedId,
                copyVideo = !reencode && burnId == null,
                sdr = toSdr,
                copyAudio = audioOk,
            )
        }
        val reasons = mediaProblems + (if (toSdr) listOf(PlaybackIssue.HdrUnsupported) else emptyList()) + if (!directSubtitleOk && burnId != null) listOf(PlaybackIssue.SubtitleNeedsBurn(subtitle!!.codec)) else emptyList()
        return Plan(mode, reasons, subtitleUnavailable = needsBurn && burnId == null && embedId == null)
    }

    /** 转码时服务器能把这条内封字幕原样带过来，本机直接播放时又能渲染 */
    fun canEmbed(subtitle: SubtitleStream, policy: PlanPolicy): Boolean =
        policy.serverEmbedsSubtitles && !subtitle.external && device.canRenderSubtitle(subtitle, transcoding = false)
}

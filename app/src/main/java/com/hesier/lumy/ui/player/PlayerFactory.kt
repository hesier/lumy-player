@file:OptIn(UnstableApi::class)

package com.hesier.lumy.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import okhttp3.OkHttpClient

/** 播放器和它的 ASS 字幕处理器（libass），两者一起创建、一起释放 */
class PlayerBundle(val player: ExoPlayer, val ass: AssHandler, val codecs: LumyCodecSelector)

/**
 * 杜比视界 Profile 7（UHD 蓝光的双层格式）很多盒子的杜比视界解码器声明支持、实际不出画面（极米上一直转圈），
 * Media3 对 P7 又没有退回 HEVC 的逻辑。[baseLayerOnly] 时改用 HEVC 解码器只解 HDR10 基础层，由播放页起播前设置。
 */
class LumyCodecSelector : MediaCodecSelector {
    @Volatile var baseLayerOnly = false

    override fun getDecoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean) =
        MediaCodecSelector.DEFAULT.getDecoderInfos(
            if (baseLayerOnly && mimeType == MimeTypes.VIDEO_DOLBY_VISION) MimeTypes.VIDEO_H265 else mimeType,
            requiresSecureDecoder,
            requiresTunnelingDecoder,
        )
}

object PlayerFactory {

    private const val MB = 1024 * 1024
    /** -3dB */
    private const val H = 0.7071f

    fun create(
        context: Context,
        http: OkHttpClient,
        headers: Map<String, String>,
        offsets: SyncOffsets,
        passthrough: Boolean = false,
    ): PlayerBundle {
        val upstream = OkHttpDataSource.Factory(http).setDefaultRequestProperties(headers)
        // ASS/SSA 交给 libass 渲染（保留字体、颜色、位置、动画），内封（MKV）和外挂都走这里
        val ass = AssHandler(AssRenderType.OVERLAY_OPEN_GL, AssHandlerConfig())
        val assParsers = AssSubtitleParserFactory(ass)
        val extractors = DefaultExtractorsFactory().withAssMkvSupport(assParsers, ass)
        val mediaSources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, upstream), extractors)
            .setSubtitleParserFactory(assParsers)

        val codecs = LumyCodecSelector()
        val renderers = object : DefaultRenderersFactory(context) {
            /**
             * 默认关闭音频直通：解码成 PCM 才能支持倍速，也适合投影仪/电视自带喇叭。
             * 接功放时可以打开直通，把 AC3/DTS 原始码流交给功放。
             */
            @Suppress("DEPRECATION")
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val builder = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                if (!passthrough) {
                    builder.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    // 多声道 PCM 下混成立体声：极米等投影仪把 5.1 PCM 路由到自带喇叭不出声的多声道输出，
                    // 音频时钟也不正常，画面跟着卡（FFmpeg 解出的 DTS / TrueHD 都是多声道）
                    builder.setAudioProcessors(arrayOf(stereoDownmix()))
                }
                return DelayedAudioSink(builder.build(), offsets)
            }

            override fun buildTextRenderers(
                context: Context,
                output: TextOutput,
                outputLooper: Looper,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>,
            ) {
                val built = ArrayList<Renderer>()
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, built)
                built.mapTo(out) { DelayedTextRenderer(it, offsets) }
            }

            /**
             * 视频不用 FFmpeg 软解：硬解报“超出能力”时 ExoPlayer 会改选软解，
             * 电视盒子软解 4K 必然卡成幻灯片。硬解不了的交给服务器转码。
             */
            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>,
            ) = super.buildVideoRenderers(
                context, EXTENSION_RENDERER_MODE_OFF, mediaCodecSelector, enableDecoderFallback,
                eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
            )
        }
            // 先用系统（硬件）解码器，系统没有的格式（DTS、TrueHD 等）再交给 FFmpeg
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            // 首选解码器初始化失败时尝试下一个，避免个别机型的硬解 bug 直接报错
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(codecs)

        val player = ExoPlayer.Builder(context, AssRenderersFactory(ass, renderers))
            .setMediaSourceFactory(mediaSources)
            .setLoadControl(loadControl())
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        ass.init(player)
        return PlayerBundle(player, ass, codecs)
    }

    /**
     * 1～8 声道都要有矩阵，缺了会在配置音频时报错（Media3 只自带 1、2 声道的系数）。
     * 声道顺序按 Android 的约定；中置和环绕按 -3dB 混入左右，丢掉 LFE。
     */
    private fun stereoDownmix() = ChannelMixingAudioProcessor().apply {
        val l = floatArrayOf(1f, 0f)
        val r = floatArrayOf(0f, 1f)
        val c = floatArrayOf(H, H)
        val lfe = floatArrayOf(0f, 0f)
        val sl = floatArrayOf(H, 0f)
        val sr = floatArrayOf(0f, H)
        val bc = floatArrayOf(0.5f, 0.5f)
        putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(1, 1))
        listOf(
            listOf(l, r),
            listOf(l, r, c),
            listOf(l, r, sl, sr), // 四声道：FL FR BL BR
            listOf(l, r, c, sl, sr),
            listOf(l, r, c, lfe, sl, sr), // 5.1：FL FR FC LFE BL BR
            listOf(l, r, c, lfe, bc, sl, sr), // 6.1：FL FR FC LFE BC SL SR
            listOf(l, r, c, lfe, sl, sr, sl, sr), // 7.1：FL FR FC LFE BL BR SL SR
        ).forEach { rows ->
            putChannelMixingMatrix(ChannelMixingMatrix(rows.size, 2, rows.flatMap { it.asList() }.toFloatArray()))
        }
    }

    /**
     * 缓冲区放在 Java 堆里。4K 原盘码率可达 80Mbps，默认按时长缓冲 50 秒会占用几百 MB，
     * 低端盒子直接 OOM，所以按可用堆大小限制缓冲字节数，并让字节上限优先于时长。
     */
    private fun loadControl(): DefaultLoadControl {
        val heap = Runtime.getRuntime().maxMemory()
        val targetBytes = (heap / 3).coerceIn(32L * MB, 160L * MB).toInt()
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 2_500, 5_000)
            .setTargetBufferBytes(targetBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }
}

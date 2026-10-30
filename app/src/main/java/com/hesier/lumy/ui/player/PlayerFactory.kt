@file:OptIn(UnstableApi::class)

package com.hesier.lumy.ui.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
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
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import okhttp3.OkHttpClient

/** 播放器和它的 ASS 字幕处理器（libass），两者一起创建、一起释放 */
class PlayerBundle(val player: ExoPlayer, val ass: AssHandler, val codecs: LumyCodecSelector, val pgs: PgsTrackSelection)

/** Route failed/unavailable Dolby Vision to HEVC; P5 retains its original, uncorrected colors. */
class LumyCodecSelector(
    private val delegate: MediaCodecSelector = MediaCodecSelector.DEFAULT,
) : MediaCodecSelector {
    @Volatile var hevcOnly = false
    @Volatile var softwareVideoMime: String? = null

    override fun getDecoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean) =
        if (mimeType == SoftwareVideoPolicy.COOK_MIME || mimeType == softwareVideoMime) emptyList() else delegate.getDecoderInfos(
            if (hevcOnly && mimeType == MimeTypes.VIDEO_DOLBY_VISION) MimeTypes.VIDEO_H265 else mimeType,
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
        allowSoftwareVideo: Boolean = false,
    ): PlayerBundle {
        val upstream = OkHttpDataSource.Factory(http).setDefaultRequestProperties(headers)
        // ASS/SSA 交给 libass 渲染（保留字体、颜色、位置、动画），内封（MKV）和外挂都走这里；
        // 没有索引的 MKV 由 ClusterSeekingExtractor 按簇查找来跳转
        val ass = AssHandler(AssRenderType.OVERLAY_OPEN_GL, AssHandlerConfig())
        val pgs = PgsTrackSelection()
        val codecs = LumyCodecSelector()
        val assParsers = PgsParserFactory(AssSubtitleParserFactory(ass))
        val extractors = DolbyVisionHevcExtractorsFactory(
            DefaultExtractorsFactory().withSeekableAssMkv(AssSubtitleParserFactory(ass), ass, pgs),
        ) { codecs.hevcOnly }
        val dataSources = DefaultDataSource.Factory(context, upstream)
        val subtitleParsers = LegacySubripParserFactory(assParsers)
        val mediaSources = com.hesier.lumy.ui.player.legacy.LegacyRmMediaSourceFactory(
            dataSources, extractors, subtitleParsers,
            DefaultMediaSourceFactory(dataSources, extractors).setSubtitleParserFactory(subtitleParsers),
        )

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

            // Keep hardware preferred; only explicitly enabled, bounded formats can use software.
            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>,
            ) {
                super.buildVideoRenderers(
                    context, EXTENSION_RENDERER_MODE_OFF, mediaCodecSelector, enableDecoderFallback,
                    eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
                )
                if (allowSoftwareVideo) out.add(
                    com.hesier.lumy.ui.player.legacy.SoftwareVideoRenderer(
                        allowedVideoJoiningTimeMs, eventHandler, eventListener,
                    ),
                )
            }
        }
            // 先用系统（硬件）解码器，系统没有的格式（DTS、TrueHD 等）再交给 FFmpeg
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            // 首选解码器初始化失败时尝试下一个，避免个别机型的硬解 bug 直接报错
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(codecs)

        val delayedRenderers = DelayedSubtitleRenderersFactory(AssRenderersFactory(ass, renderers), offsets, ass)
        val player = ExoPlayer.Builder(context, delayedRenderers)
            .setMediaSourceFactory(SupMediaSourceFactory(mediaSources, dataSources))
            .setLoadControl(loadControl())
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        ass.init(player)
        return PlayerBundle(player = player, ass = ass, codecs = codecs, pgs = pgs)
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

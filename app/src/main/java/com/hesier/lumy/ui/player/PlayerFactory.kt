@file:OptIn(UnstableApi::class)

package com.hesier.lumy.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
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
class PlayerBundle(val player: ExoPlayer, val ass: AssHandler)

object PlayerFactory {

    private const val MB = 1024 * 1024

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
                if (!passthrough) builder.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
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

        val player = ExoPlayer.Builder(context, AssRenderersFactory(ass, renderers))
            .setMediaSourceFactory(mediaSources)
            .setLoadControl(loadControl())
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        ass.init(player)
        return PlayerBundle(player, ass)
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

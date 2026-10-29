@file:OptIn(UnstableApi::class)

package com.hesier.lumy.ui.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.hesier.lumy.R
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.render.AssRenderer

/**
 * 字幕 / 音频延迟，播放中随时可改，立即生效。正数表示“推迟”。
 * Media3 没有公开的延迟接口，这里在渲染时间上做偏移。
 */
class SyncOffsets {
    @Volatile var subtitleDelayMs: Long = 0
    @Volatile var audioDelayMs: Long = 0
}

fun formatDelay(context: Context, ms: Long): String = context.getString(R.string.delay_seconds, ms / 1000f)

/** Wrap the final list: libass appends its renderer after the default text renderers. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class DelayedSubtitleRenderersFactory(
    private val factory: RenderersFactory,
    private val offsets: SyncOffsets,
    private val ass: AssHandler,
) : RenderersFactory by factory {
    override fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput,
    ): Array<Renderer> = factory.createRenderers(
        eventHandler, videoRendererEventListener, audioRendererEventListener, textRendererOutput, metadataRendererOutput,
    ).map { renderer ->
        when {
            renderer is AssRenderer -> DelayedTextRenderer(renderer, offsets) {
                // Paused playback may never reach libass's throttled frame callback.
                ass.videoTimeCallback?.invoke(ass.videoTime)
            }
            renderer.trackType == C.TRACK_TYPE_TEXT -> DelayedTextRenderer(renderer, offsets)
            else -> renderer
        }
    }.toTypedArray()
}

/** 字幕渲染器拿到的时间减去延迟：字幕按“更早的时间”显示，即整体推迟 */
internal class DelayedTextRenderer(
    renderer: Renderer,
    private val offsets: SyncOffsets,
    private val onDelayChanged: (() -> Unit)? = null,
) : ForwardingRenderer(renderer) {
    private var appliedDelayMs = offsets.subtitleDelayMs

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val delayMs = offsets.subtitleDelayMs
        super.render(positionUs - delayMs * 1000, elapsedRealtimeUs)
        if (delayMs != appliedDelayMs) {
            appliedDelayMs = delayMs
            onDelayChanged?.invoke()
        }
    }
}

/**
 * 播放器以音频时钟为准同步画面。时钟加上延迟后，画面按更晚的时间渲染（画面提前），
 * 相对来说声音就“推迟”了；负值则画面滞后、声音提前。
 */
internal class DelayedAudioSink(sink: AudioSink, private val offsets: SyncOffsets) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        if (position == AudioSink.CURRENT_POSITION_NOT_SET) return position
        return (position + offsets.audioDelayMs * 1000).coerceAtLeast(0)
    }
}

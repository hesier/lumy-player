@file:OptIn(UnstableApi::class)

package com.hesier.lumy.ui.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.hesier.lumy.R

/**
 * 字幕 / 音频延迟，播放中随时可改，立即生效。正数表示“推迟”。
 * Media3 没有公开的延迟接口，这里在渲染时间上做偏移。
 */
class SyncOffsets {
    @Volatile var subtitleDelayMs: Long = 0
    @Volatile var audioDelayMs: Long = 0
}

fun formatDelay(context: Context, ms: Long): String = context.getString(R.string.delay_seconds, ms / 1000f)

/** 字幕渲染器拿到的时间减去延迟：字幕按“更早的时间”显示，即整体推迟 */
internal class DelayedTextRenderer(renderer: Renderer, private val offsets: SyncOffsets) : ForwardingRenderer(renderer) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(positionUs - offsets.subtitleDelayMs * 1000, elapsedRealtimeUs)
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

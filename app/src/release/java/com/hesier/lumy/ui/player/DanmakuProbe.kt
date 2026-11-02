package com.hesier.lumy.ui.player

import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.hesier.lumy.ui.widget.Option

/** Keeps performance fixtures and their dependency out of release builds. */
@Suppress("UNUSED_PARAMETER")
internal class DanmakuProbe(view: PlayerView, player: Player, positionMs: () -> Long) {
    fun menuOption(): Pair<Option, () -> Unit>? = null
    fun reset() = Unit
    fun release() = Unit
}

package com.hesier.lumy.ui.player

import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.hesier.lumy.ui.widget.Option

/** Keeps generated performance fixtures and their menu out of release builds. */
@Suppress("UNUSED_PARAMETER")
internal class DanmakuProbe(
    view: PlayerView,
    player: Player,
    onProfileChanged: () -> Unit = {},
    positionMs: () -> Long,
) {
    val profile = 0
    fun menuOption(): Pair<Option, () -> Unit>? = null
    fun reset() = Unit
    fun release() = Unit
}

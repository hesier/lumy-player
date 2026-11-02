@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.content.Context
import android.content.SharedPreferences
import android.graphics.RectF
import android.os.SystemClock
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.hesier.lumy.net.danmaku.DanmakuComment
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.ui.DanmakuPlayer
import com.kuaishou.akdanmaku.ui.DanmakuView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** 真实弹幕运行层；播放器绝对时钟驱动有限窗口，输入替换和销毁均取消后台工作。 */
internal class DanmakuOverlay(
    private val host: PlayerView,
    private val player: Player,
    private val positionMs: () -> Long,
    preferences: SharedPreferences = host.context.getSharedPreferences("settings", Context.MODE_PRIVATE),
) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = DanmakuTestSession(preferences, host, ::refreshSettings)
    val metrics = DanmakuMetrics()
    private var layer: DanmakuLayer? = null
    private var engine: DanmakuPlayer? = null
    private var driver: DanmakuFrameDriver? = null
    private var inputJob: Job? = null
    private var preparation: Job? = null
    private var all: List<DanmakuComment>? = null
    private var admitted: List<DanmakuComment>? = null
    private var admissionStyle: DanmakuTestPreferences? = null
    private var active: DanmakuWindow? = null
    private var prepared: DanmakuWindow? = null
    private var sourceOffsetMs = 0L
    private var generation = 0L
    private var request = 0L
    private var released = false
    private var lastPlaying: Boolean? = null
    private var lastSpeed = Float.NaN
    private var ticks = 0
    private var driftSamples = 0
    private var lastCorrectionMs = 0L
    private val monitor = object : Runnable {
        override fun run() {
            if (released || all == null) return
            sync(forceSeek = false, checkDrift = true)
            if (engine != null && session.sample(metrics, player.isPlaying)) driver?.setDensityDivisor(session.divisor)
            val window = active
            if (window != null && canDisplay && prepared == null && preparation?.isActive != true &&
                window.endMs - commentPositionMs <= PREFETCH_MS) {
                prepare(commentPositionMs + PREFETCH_MS, activate = false)
            }
            if (++ticks % LOG_TICKS == 0) com.hesier.lumy.util.DebugLog.i("LumyDanmaku",
                "runtime video=${positionMs()} danmaku=$currentTimeMs visible=$visibleCount divisor=${session.divisor} ${metrics.report(engine)}")
            host.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    init { player.addListener(this) }

    fun setComments(items: List<DanmakuComment>, sourceOffsetMs: Long = 0L) {
        if (released) return
        clear()
        this.sourceOffsetMs = sourceOffsetMs
        val version = generation
        inputJob = scope.launch {
            val sorted = withContext(Dispatchers.Default) { DanmakuWindow.sort(items) }
            if (released || version != generation) return@launch
            all = sorted
            sync(forceSeek = true)
            host.postDelayed(monitor, SYNC_INTERVAL_MS)
        }
    }

    private val rawPositionMs: Long get() = positionMs() - session.settings.offsetMs - sourceOffsetMs
    private val commentPositionMs: Long get() = rawPositionMs.coerceAtLeast(0)
    private val canDisplay: Boolean get() = all?.isNotEmpty() == true && session.settings.enabled &&
        session.settings.modes.isNotEmpty() && rawPositionMs >= 0

    private fun refreshSettings() {
        if (released) return
        cancelPreparation()
        removeEngine()
        session.resetDensity()
        sync(forceSeek = true)
    }

    private fun cancelPreparation() {
        request++
        preparation?.cancel()
        preparation = null
        prepared = null
    }

    private fun prepare(position: Long, activate: Boolean) {
        val source = all ?: return
        cancelPreparation()
        val version = generation
        val serial = request
        val style = session.settings
        val cached = admitted.takeIf { admissionStyle?.density == style.density && admissionStyle?.modes == style.modes }
        preparation = scope.launch {
            val (selected, window) = withContext(Dispatchers.Default) {
                val selected = cached ?: DanmakuWindow.admit(source, style)
                selected to DanmakuWindow.prepare(selected, position, style)
            }
            if (released || generation != version || request != serial || !canDisplay) return@launch
            admitted = selected
            admissionStyle = style
            preparation = null
            if (activate) {
                // 播放器可能在后台准备期间再次 seek，旧窗口不能恢复旧时间。
                if (commentPositionMs in window.positionMs..window.endMs) install(window)
                else prepare(commentPositionMs, activate = true)
            } else prepared = window
        }
    }

    private fun install(window: DanmakuWindow) {
        removeEngine()
        prepared = null
        active = window
        val view = DanmakuView(host.context).apply {
            isFocusable = false
            isClickable = false
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val parent = host.subtitleView?.parent as? ViewGroup ?: host
        layer = DanmakuLayer(host.context, metrics).also {
            it.addView(view, ViewGroup.LayoutParams(-1, -1))
            parent.addView(it, parent.indexOfChild(host.subtitleView).coerceAtLeast(0), ViewGroup.LayoutParams(-1, -1))
        }
        DanmakuPlayer.isManualStep = true
        DanmakuConfig.CACHE_POOL_MAX_MEMORY_SIZE = CACHE_BYTES
        val style = session.settings
        val comments = DanmakuPlayer(DanmakuTextRenderer(style.strokeDp))
        comments.bindView(view)
        comments.start(style.config(DanmakuDensityFilter().apply { divisor = session.divisor }))
        engine = comments
        driver = DanmakuFrameDriver(comments, view, metrics)
        layer?.onDrawn = driver!!::onDrawn
        driver?.load(window.items)
        lastPlaying = null
        lastSpeed = Float.NaN
        sync(forceSeek = true)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (released) return
        if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_PLAYBACK_PARAMETERS_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
            sync(forceSeek = events.contains(Player.EVENT_POSITION_DISCONTINUITY))
        }
    }

    private fun sync(forceSeek: Boolean, checkDrift: Boolean = false) {
        if (!canDisplay) {
            cancelPreparation()
            removeEngine()
            return
        }
        val position = commentPositionMs
        val ready = prepared
        if (ready != null && position in ready.positionMs..ready.endMs) {
            install(ready)
            return
        }
        val window = active
        if (engine == null || window == null || position !in window.positionMs..window.endMs) {
            if (forceSeek || preparation?.isActive != true) prepare(position, activate = true)
            return
        }
        val playing = player.isPlaying
        val speed = player.playbackParameters.speed
        val correct = checkDrift && !forceSeek && needsCorrection(position, playing)
        val seek = forceSeek || correct
        if (seek || playing != lastPlaying || speed != lastSpeed) {
            if (playing != lastPlaying) metrics.resetIntervals()
            driver?.sync(playing, speed, position.takeIf { seek })
            lastPlaying = playing
            lastSpeed = speed
            if (correct) { metrics.corrections++; lastCorrectionMs = SystemClock.elapsedRealtime() }
            if (seek) driftSamples = 0
        }
    }

    private fun needsCorrection(position: Long, playing: Boolean): Boolean {
        driftSamples = if (playing && abs(currentTimeMs - position) > MAX_DRIFT_MS) driftSamples + 1 else 0
        return driftSamples >= DRIFT_SAMPLES && SystemClock.elapsedRealtime() - lastCorrectionMs >= CORRECTION_COOLDOWN_MS
    }

    val appliedConfig: DanmakuConfig? get() = engine?.let(AkDanmakuAccess::config)
    val currentTimeMs: Long get() = engine?.getCurrentTimeMs() ?: 0L
    val visibleCount: Int get() = engine?.getDanmakusInRect(RectF(0f, 0f, host.width.toFloat(), host.height.toFloat()))?.size ?: 0
    val densityDivisor: Int get() = session.divisor
    val visibleIds: List<Long> get() = engine?.getDanmakusInRect(
        RectF(0f, 0f, host.width.toFloat(), host.height.toFloat()))?.map { it.data.danmakuId } ?: emptyList()

    private fun removeEngine() {
        driver?.release()
        layer?.onDrawn = null
        layer?.let { (it.parent as? ViewGroup)?.removeView(it) }
        driver = null
        engine = null
        layer = null
        active = null
    }

    fun clear() {
        generation++
        inputJob?.cancel()
        inputJob = null
        cancelPreparation()
        all = null
        admitted = null
        admissionStyle = null
        host.removeCallbacks(monitor)
        removeEngine()
        session.resetDensity()
        ticks = 0
        driftSamples = 0
        lastCorrectionMs = 0L
    }

    fun release() {
        if (released) return
        released = true
        player.removeListener(this)
        session.release()
        clear()
        scope.cancel()
    }

    private companion object {
        const val LOG_TICKS = 10
        const val CACHE_BYTES = 8 * 1024 * 1024
        const val SYNC_INTERVAL_MS = 1_000L
        const val PREFETCH_MS = 30_000L
        const val MAX_DRIFT_MS = 300L
        const val DRIFT_SAMPLES = 3
        const val CORRECTION_COOLDOWN_MS = 10_000L
    }
}

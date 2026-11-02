@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.content.Context
import android.content.SharedPreferences
import android.graphics.RectF
import android.os.SystemClock
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.hesier.lumy.R
import com.hesier.lumy.ui.widget.Option
import com.hesier.lumy.ui.widget.OptionsPanel
import com.hesier.lumy.util.DebugLog
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItemData
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

/** Debug-only bounded input, serial frame scheduling and CPU measurements. */
internal class DanmakuProbe(
    private val host: PlayerView,
    private val player: Player,
    preferences: SharedPreferences = host.context.getSharedPreferences("settings", Context.MODE_PRIVATE),
    private val positionMs: () -> Long,
) : Player.Listener {
    private val session = DanmakuTestSession(preferences, host, ::refreshSettings)
    var profile = 0
        private set
    var metrics = DanmakuMetrics()
        private set
    private var layer: DanmakuLayer? = null
    private var engine: DanmakuPlayer? = null
    private var driver: DanmakuFrameDriver? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var preparation: Job? = null
    private var prepared: Prepared? = null
    private var dataStartMs = 0L
    private var dataEndMs = 0L
    private var lastPlaying: Boolean? = null
    private var lastSpeed = Float.NaN
    private var driftSamples = 0
    private var lastCorrectionMs = 0L
    private var ticks = 0
    private var released = false
    private val labels = listOf(R.string.danmaku_probe_off, R.string.danmaku_probe_normal, R.string.danmaku_probe_stress)
    private val monitor = object : Runnable {
        override fun run() {
            if (profile == 0 || released) return
            sync(forceSeek = false, checkDrift = true)
            if (engine != null && session.sample(metrics, player.isPlaying)) driver?.setDensityDivisor(session.divisor)
            if (engine != null && dataEndMs - commentPositionMs < PREFETCH_MS &&
                prepared == null && preparation?.isActive != true) prepare(dataEndMs, activate = false)
            if (++ticks % LOG_TICKS == 0) engine?.let {
                DebugLog.i(TAG, "profile=$profile video=${positionMs()} danmaku=$currentTimeMs " +
                    "playing=${player.isPlaying} visible=$visibleCount divisor=${session.divisor} ui=${host.width}x${host.height} ${performanceReport}")
            }
            host.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    init { player.addListener(this) }

    fun menuOption(): Pair<Option, () -> Unit>? =
        Option(host.context.getString(R.string.danmaku_probe_title), host.context.getString(if (profile != 0 && !session.settings.enabled) R.string.danmaku_probe_settings_off else labels[profile])) to {
            OptionsPanel(host.context, host.context.getString(R.string.danmaku_probe_title),
                labels.mapIndexed { index, label -> Option(host.context.getString(label), selected = profile == index) }
            ) { setProfile(it) }.show()
        }

    fun setProfile(value: Int) {
        check(!released)
        require(value in labels.indices)
        reset()
        profile = value
        metrics = DanmakuMetrics()
        if (value == 0) return
        refreshSettings()
        host.postDelayed(monitor, SYNC_INTERVAL_MS)
    }

    private fun refreshSettings() {
        if (released || profile == 0) return
        preparation?.cancel()
        prepared = null
        removeEngine()
        metrics = DanmakuMetrics()
        if (canDisplay) prepare(commentPositionMs, activate = true)
    }

    private val canDisplay: Boolean get() = session.settings.enabled && session.settings.modes.isNotEmpty() &&
        positionMs() >= session.settings.offsetMs

    private val commentPositionMs: Long get() = (positionMs() - session.settings.offsetMs).coerceAtLeast(0)

    private fun prepare(position: Long, activate: Boolean) {
        preparation?.cancel()
        val request = DanmakuInput.Request(profile, position.coerceAtLeast(0),
            List(SAMPLE_VARIANTS) { host.context.getString(R.string.danmaku_probe_sample, it + 1) }, session.settings)
        preparation = scope.launch {
            val items = withContext(Dispatchers.Default) { DanmakuInput.fixture(request) }
            val result = Prepared(request, items)
            if (activate) install(result) else prepared = result
        }
    }

    private fun install(data: Prepared) {
        removeEngine()
        prepared = null
        dataStartMs = data.request.startMs
        dataEndMs = data.request.endMs
        val view = DanmakuView(host.context).apply {
            isFocusable = false
            isClickable = false
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val parent = host.subtitleView?.parent as? ViewGroup ?: host
        layer = DanmakuLayer(host.context, metrics).also {
            it.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            parent.addView(it, parent.indexOfChild(host.subtitleView).coerceAtLeast(0), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        // All debug Ak instances use the same manual scheduler; release never restores a racing global flag.
        DanmakuPlayer.isManualStep = true
        DanmakuConfig.CACHE_POOL_MAX_MEMORY_SIZE = CACHE_BYTES
        val style = data.request.preferences
        val comments = DanmakuPlayer(DanmakuTextRenderer(style.strokeDp))
        comments.bindView(view)
        comments.start(style.config(DanmakuDensityFilter().apply { divisor = session.divisor }))
        engine = comments
        driver = DanmakuFrameDriver(comments, view, metrics)
        layer?.onDrawn = driver!!::onDrawn
        // Entity creation and input installation run on the same worker as frame updates.
        driver?.load(data.items)
        lastPlaying = null
        lastSpeed = Float.NaN
        sync(forceSeek = true)
        DebugLog.i(TAG, "start profile=$profile range=$dataStartMs..$dataEndMs cacheBudget=$CACHE_BYTES")
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (engine == null) return
        if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_PLAYBACK_PARAMETERS_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
            sync(forceSeek = events.contains(Player.EVENT_POSITION_DISCONTINUITY))
        }
    }

    private fun sync(forceSeek: Boolean, checkDrift: Boolean = false) {
        if (!canDisplay) {
            preparation?.cancel()
            prepared = null
            removeEngine()
            return
        }
        if (engine == null) {
            if (preparation?.isActive != true) prepare(commentPositionMs, activate = true)
            return
        }
        val position = commentPositionMs
        if (position !in dataStartMs..dataEndMs) {
            val ready = prepared
            if (ready != null && position in ready.request.startMs..ready.request.endMs) install(ready)
            else if (preparation?.isActive != true || forceSeek) prepare(position, activate = true)
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

    val appliedPreferences: DanmakuTestPreferences get() = session.settings
    val appliedConfig: DanmakuConfig? get() = engine?.let(AkDanmakuAccess::config)
    val densityDivisor: Int get() = session.divisor
    val performanceReport: String get() = metrics.report(engine)
    val currentTimeMs: Long get() = engine?.getCurrentTimeMs() ?: 0L
    val visibleCount: Int get() = engine?.getDanmakusInRect(RectF(0f, 0f, host.width.toFloat(), host.height.toFloat()))?.size ?: 0
    val visibleComments: List<DanmakuItemData> get() = engine?.getDanmakusInRect(RectF(0f, 0f,
        host.width.toFloat(), host.height.toFloat()))?.map { it.data } ?: emptyList()
    val visibleIds: List<Long> get() = engine?.getDanmakusInRect(RectF(0f, 0f,
        host.width.toFloat(), host.height.toFloat()))?.map { it.data.danmakuId } ?: emptyList()
    val cacheSnapshot: LongArray get() = engine?.let { com.kuaishou.akdanmaku.LumyCacheBudget.snapshot(
        AkDanmakuAccess.cacheManager(it)) } ?: LongArray(5)

    private fun removeEngine() {
        driver?.release()
        layer?.onDrawn = null
        layer?.let { (it.parent as? ViewGroup)?.removeView(it) }
        driver = null
        engine = null
        layer = null
    }

    fun reset() {
        host.removeCallbacks(monitor)
        preparation?.cancel()
        prepared = null
        removeEngine()
        profile = 0
        ticks = 0
        session.resetDensity()
        driftSamples = 0
        lastCorrectionMs = 0L
    }

    fun release() {
        if (released) return
        released = true
        player.removeListener(this)
        session.release()
        reset()
        scope.cancel()
    }

    private data class Prepared(val request: DanmakuInput.Request, val items: List<DanmakuItemData>)

    private companion object {
        const val TAG = "LumyDanmaku"
        const val CACHE_BYTES = 8 * 1024 * 1024
        const val SAMPLE_VARIANTS = 12
        const val SYNC_INTERVAL_MS = 1_000L
        const val MAX_DRIFT_MS = 300L
        const val DRIFT_SAMPLES = 3
        const val CORRECTION_COOLDOWN_MS = 10_000L
        const val PREFETCH_MS = 30_000L
        const val LOG_TICKS = 10
    }
}

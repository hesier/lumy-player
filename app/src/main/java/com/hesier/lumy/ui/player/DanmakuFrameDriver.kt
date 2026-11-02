package com.hesier.lumy.ui.player

import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import com.kuaishou.akdanmaku.LumyCacheBudget
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.ui.DanmakuPlayer
import com.kuaishou.akdanmaku.ui.DanmakuView
import java.util.concurrent.atomic.AtomicBoolean

/** Drives the existing Ak engine in manual mode, with one serial update per vsync. */
internal class DanmakuFrameDriver(
    private val comments: DanmakuPlayer,
    private val view: DanmakuView,
    private val metrics: DanmakuMetrics,
) : Choreographer.FrameCallback {
    private val thread = HandlerThread("LumyDanmakuFrames").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var choreographer: Choreographer
    private val layer = view.parent as DanmakuLayer
    private val requested = AtomicBoolean(false)
    private val frameGate = DanmakuFrameGate()
    private val layoutListener = android.view.View.OnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
        if (r - l != oldR - oldL || b - t != oldB - oldT) requestFrame()
    }
    private var callbackPosted = false
    private var playing = false
    private var speed = Float.NaN
    private val rollingDurationMs = AkDanmakuAccess.config(comments).rollingDurationMs
    @Volatile private var released = false

    init {
        view.addOnLayoutChangeListener(layoutListener)
        handler.post { choreographer = Choreographer.getInstance() }
        LumyCacheBudget.onReady(AkDanmakuAccess.cacheManager(comments), ::requestFrame)
    }

    fun load(items: List<DanmakuItemData>) {
        handler.post { if (!released) comments.updateData(items) }
    }

    fun sync(playing: Boolean, speed: Float, seekMs: Long?) {
        handler.post {
            if (released) return@post
            if (speed != this.speed) {
                comments.updatePlaySpeed(speed)
                this.speed = speed
                metrics.speedChanges++
            }
            if (seekMs != null) {
                comments.seekTo(seekMs)
                metrics.seeks++
            }
            if (this.playing != playing) {
                if (playing) { comments.start(); metrics.starts++ }
                else { comments.pause(); metrics.pauses++ }
            } else if (!playing && seekMs != null) {
                // Upstream seek starts its timer even when playback is paused.
                comments.pause()
            }
            this.playing = playing
            requestFrame()
        }
    }

    fun setDensityDivisor(divisor: Int) {
        handler.post {
            if (released) return@post
            val config = AkDanmakuAccess.config(comments)
            val filter = config.dataFilter.filterIsInstance<DanmakuDensityFilter>().single()
            if (filter.divisor == divisor) return@post
            filter.divisor = divisor
            config.updateFilter()
            config.updateRetainer()
            config.updateLayout()
            comments.updateConfig(config)
            requestFrame()
        }
    }

    fun requestFrame() {
        if (released || !requested.compareAndSet(false, true)) return
        handler.post {
            requested.set(false)
            if (released || callbackPosted) return@post
            callbackPosted = true
            choreographer.postFrameCallback(this)
        }
    }

    fun onDrawn() {
        if (frameGate.consume()) handler.post {
            if (released) return@post
            // Consume this display tick now, and cancel a competing cache/vsync request.
            choreographer.removeFrameCallback(this)
            callbackPosted = false
            produceFrame()
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        callbackPosted = false
        produceFrame()
    }

    private fun produceFrame() {
        if (released) return
        // Wait for consumption without blocking the worker or skipping a display tick.
        if (!frameGate.acquire()) return
        val started = System.nanoTime()
        // 上游会随视口宽度改写时长；只在它改变时恢复用户选择的速度。
        AkDanmakuAccess.config(comments).let { config ->
            if (config.rollingDurationMs != rollingDurationMs) {
                config.rollingDurationMs = rollingDurationMs
                config.updateRetainer()
                config.updateLayout()
                config.updateVisibility()
            }
        }
        AkDanmakuAccess.advance(comments, !playing)
        metrics.update.record(System.nanoTime() - started)
        view.postInvalidateOnAnimation()
        // A dirty child may update its render node without invoking its parent's dispatchDraw.
        layer.postInvalidateOnAnimation()
        if (playing) requestFrame()
    }

    fun release() {
        if (released) return
        released = true
        view.removeOnLayoutChangeListener(layoutListener)
        LumyCacheBudget.onReady(AkDanmakuAccess.cacheManager(comments), null)
        handler.post {
            choreographer.removeFrameCallback(this)
            comments.release()
            thread.quitSafely()
        }
    }
}

package com.hesier.lumy.ui.player

import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import com.hesier.lumy.util.DebugLog

/** Anonymous startup measurements; no server, media or exception strings enter the log. */
internal class PlaybackStartupTiming(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val output: (String) -> Unit = { message -> logHandler.post { DebugLog.i("LumyStartup", message) } },
) {
    class Attempt internal constructor(val number: Long, val startedMs: Long) {
        val key = "lumy-startup-$number"
        internal val stages = mutableMapOf<Stage, Long>()
        internal var completed = false
        internal var networkRequests = 0
    }

    enum class Stage {
        BEGIN, DETAIL_BEGIN, DETAIL_END, PLAN_BEGIN, PLAN_END, SOURCE_BEGIN, STREAM_SELECTION_SAVED,
        SOURCE_END, PREPARE, READY, VIDEO_DECODER, AUDIO_DECODER, FIRST_FRAME,
        HTTP_FIRST_READ, HTTP_TRANSFER_END, HTTP_LOAD_COMPLETE,
    }

    enum class End { REPLACED, CANCELLED, ERROR, FIRST_FRAME }

    data class Measurement(val durationMs: Long? = null, val bytes: Long? = null, val eventMs: Long? = null)

    private var sequence = 0L
    private var active: Attempt? = null

    @Synchronized fun begin(withDetail: Boolean = false): Attempt {
        end(End.REPLACED)
        return Attempt(++sequence, clock()).also {
            active = it
            mark(it, Stage.BEGIN)
            if (withDetail) mark(it, Stage.DETAIL_BEGIN)
        }
    }

    @Synchronized fun isActive(attempt: Attempt?): Boolean = attempt != null && attempt === active

    @Synchronized fun forKey(key: String?): Attempt? = active?.takeIf { it.key == key }

    @Synchronized fun nextNetwork(attempt: Attempt): Int? {
        if (!isActive(attempt) || attempt.completed || attempt.networkRequests >= MAX_NETWORK_REQUESTS) return null
        return ++attempt.networkRequests
    }

    @Synchronized fun network(attempt: Attempt, number: Int, fields: String) {
        if (isActive(attempt)) output("attempt=${attempt.number} http=$number $fields")
    }

    @Synchronized fun mark(attempt: Attempt?, stage: Stage, measurement: Measurement = Measurement()) {
        if (!isActive(attempt) || attempt == null || stage in attempt.stages) return
        if (attempt.completed && stage !in AFTER_FRAME_STAGES) return
        val now = clock()
        attempt.stages[stage] = now
        val begin = when (stage) {
            Stage.DETAIL_END -> Stage.DETAIL_BEGIN
            Stage.PLAN_END -> Stage.PLAN_BEGIN
            Stage.SOURCE_END -> Stage.SOURCE_BEGIN
            else -> null
        }?.let(attempt.stages::get)
        val duration = measurement.durationMs ?: begin?.let { now - it }
        output("attempt=${attempt.number} stage=$stage elapsedMs=${now - attempt.startedMs}" +
            (duration?.let { " durationMs=$it" } ?: "") +
            (measurement.bytes?.let { " bytes=$it" } ?: "") +
            (measurement.eventMs?.let { " eventElapsedMs=${it - attempt.startedMs}" } ?: ""))
    }

    @Synchronized fun renderer(attempt: Attempt?, stage: Stage, eventMs: Long, durationMs: Long? = null) {
        val preparedMs = attempt?.stages?.get(Stage.PREPARE) ?: return
        // Renderer callbacks can be queued before a new source replaces the timeline.
        if (eventMs < preparedMs) return
        mark(attempt, stage, Measurement(durationMs = durationMs, eventMs = eventMs))
        if (stage == Stage.FIRST_FRAME && isActive(attempt)) complete(attempt!!, End.FIRST_FRAME)
    }

    @Synchronized fun end(reason: End) {
        active?.let { if (!it.completed) complete(it, reason) }
        active = null
    }

    private fun complete(attempt: Attempt, reason: End) {
        if (attempt.completed) return
        attempt.completed = true
        output("attempt=${attempt.number} end=$reason elapsedMs=${clock() - attempt.startedMs}" +
            " firstReadScope=keyed_single_file_http loadScope=media3_http_load_not_ttfb")
    }

    private companion object {
        const val MAX_NETWORK_REQUESTS = 12
        val logHandler by lazy { Handler(Looper.getMainLooper()) }
        val AFTER_FRAME_STAGES = setOf(Stage.READY, Stage.VIDEO_DECODER, Stage.AUDIO_DECODER,
            Stage.HTTP_FIRST_READ, Stage.HTTP_TRANSFER_END, Stage.HTTP_LOAD_COMPLETE)
    }
}

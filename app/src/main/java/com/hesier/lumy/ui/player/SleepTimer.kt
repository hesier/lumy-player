package com.hesier.lumy.ui.player

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One playback session owns the deadline, so changing episodes cannot extend it. */
internal class SleepTimer(
    private val scope: CoroutineScope,
    private val onExpired: () -> Unit,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private var job: Job? = null
    private var deadline: Long? = null
    var optionMinutes = 0
        private set
    val endOfItem get() = optionMinutes == END_OF_ITEM
    val remainingMinutes: Int? get() = deadline?.let {
        ((it - now()).coerceAtLeast(0) + MINUTE_MS - 1).div(MINUTE_MS).toInt()
    }

    fun set(minutes: Int) {
        require(minutes in OPTIONS)
        clear()
        optionMinutes = minutes
        if (minutes <= 0) return
        val end = now() + minutes * MINUTE_MS
        deadline = end
        job = scope.launch {
            while (now() < end) delay((end - now()).coerceAtLeast(1).coerceAtMost(1000))
            expire()
        }
    }

    fun expire() {
        if (optionMinutes == 0) return
        clear()
        onExpired()
    }

    fun clear() {
        job?.cancel()
        job = null
        deadline = null
        optionMinutes = 0
    }

    companion object {
        const val END_OF_ITEM = -1
        val OPTIONS = listOf(0, 15, 30, 60, 90, END_OF_ITEM)
        private const val MINUTE_MS = 60_000L
    }
}

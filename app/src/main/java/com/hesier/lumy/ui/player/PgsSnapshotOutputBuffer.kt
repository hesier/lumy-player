@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.text.TextOutput

internal data class PgsSnapshotOutputBuffer(
    val sample: PgsSnapshotDecoder.Sample,
    val cues: List<Cue>,
    val error: Throwable? = null,
)

/** A single pending message prevents a busy playback thread from retaining old Bitmap frames. */
internal class PgsSnapshotOutput(private val output: TextOutput, looper: Looper) {
    private data class Delivery(val generation: Long, val group: CueGroup)
    private val handler = Handler(looper)
    private val lock = Any()
    private var generation = 0L
    private var pending: Delivery? = null
    private var scheduled = false
    private val deliver = Runnable {
        val delivery = synchronized(lock) {
            scheduled = false
            pending.also { pending = null }
        }
        if (delivery != null && synchronized(lock) { delivery.generation == generation }) {
            // Looper serialization keeps an in-flight delivery before a racing reset's clear.
            // Never hold the playback-thread lock while calling application listeners.
            @Suppress("DEPRECATION")
            output.onCues(delivery.group.cues)
            output.onCues(delivery.group)
        }
    }

    fun reset(positionUs: Long): Long = synchronized(lock) {
        generation++
        enqueue(CueGroup(emptyList(), positionUs))
        generation
    }

    fun publish(group: CueGroup, expectedGeneration: Long) = synchronized(lock) {
        if (expectedGeneration == generation) enqueue(group)
    }

    private fun enqueue(group: CueGroup) {
        pending = Delivery(generation, group)
        if (!scheduled) {
            scheduled = true
            if (!handler.post(deliver)) {
                scheduled = false
                pending = null
            }
        }
    }
}

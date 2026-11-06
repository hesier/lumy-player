package com.hesier.lumy.tv

import android.content.Context
import com.hesier.lumy.LumyApp
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** Identifies the local account and the server selection that initiated an operation. */
class WatchNextSession internal constructor(val serverId: String, internal val generation: Long, internal val sequence: Long)

internal object WatchNextAccess {
    private val generation = AtomicLong()
    private val sequence = AtomicLong()
    private val mutex = Mutex()
    private val lastSequence = mutableMapOf<String, Long>()
    private var clearedGeneration = 0L
    private var lastSnapshotSequence = 0L

    fun capture(server: MediaServer): WatchNextSession? {
        if (!LumyApp.instance.settings.watchNextEnabled) return null
        val revision = generation.get()
        val entry = LumyApp.instance.currentEntry ?: return null
        if (entry.session != server.session) return null
        return WatchNextSession(entry.id, revision, sequence.incrementAndGet())
    }

    fun isCurrent(session: WatchNextSession): Boolean = session.generation == generation.get() &&
        session.serverId == LumyApp.instance.currentEntry?.id && LumyApp.instance.settings.watchNextEnabled

    fun invalidate() {
        generation.incrementAndGet()
    }

    // Only called while holding the provider mutex. Retain tombstones for deleted items.
    fun accept(session: WatchNextSession, keys: List<String>): Boolean {
        if (keys.any { (lastSequence[it] ?: 0L) > session.sequence }) return false
        keys.forEach { lastSequence[it] = session.sequence }
        return true
    }

    fun acceptSnapshot(session: WatchNextSession): Boolean {
        if (session.sequence < lastSnapshotSequence) return false
        lastSnapshotSequence = session.sequence
        return true
    }

    suspend fun run(context: Context, session: WatchNextSession? = null, block: () -> Unit) =
        withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    val revision = generation.get()
                    if (session != null && (session.generation != revision ||
                            session.serverId != LumyApp.instance.currentEntry?.id ||
                            !LumyApp.instance.settings.watchNextEnabled)) return@withLock
                    // A new account must be cleared before its first write, even if cleanup is still queued.
                    if (clearedGeneration != revision) {
                        WatchNextManager.clearInternal(context)
                        clearedGeneration = revision
                        lastSequence.clear()
                        lastSnapshotSequence = 0L
                    }
                    block()
                }
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.i("WatchNextManager", "Failed to access Watch Next: ${e.message}")
                Result.failure(e)
            }
        }
}

package com.hesier.lumy.tv

import android.content.Context
import android.database.sqlite.SQLiteException
import android.os.Build
import androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms
import com.hesier.lumy.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

internal class WatchNextUnavailableException : UnsupportedOperationException("Watch Next is unavailable")

/** Capability belongs to the device, never to the selected media account. Restart retries discovery. */
internal object WatchNextSupport {
    private class State {
        val mutex = Mutex()
        @Volatile var available: Boolean? = null
    }

    // Activity/Application contexts share a key; isolated test contexts have their own storage root.
    private val states = ConcurrentHashMap<String, State>()
    private fun state(context: Context) = states.getOrPut(context.filesDir.absolutePath) { State() }
    fun hasApi(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    fun isSupported(context: Context): Boolean = hasApi() && state(context).available == true
    fun isUnavailable(context: Context): Boolean = !hasApi() || state(context).available == false

    suspend fun ensure(context: Context): Boolean {
        if (!hasApi()) return false
        val state = state(context)
        state.available?.let { return it }
        return withContext(Dispatchers.IO) {
            state.mutex.withLock {
                state.available ?: probe(context).also { state.available = it }
            }
        }
    }

    private fun probe(context: Context): Boolean = try {
        val columns = arrayOf(WatchNextPrograms._ID, WatchNextPrograms.COLUMN_CONTENT_ID,
            WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_DATA,
            WatchNextPrograms.COLUMN_POSTER_ART_URI)
        val cursor = context.contentResolver.query(WatchNextPrograms.CONTENT_URI, columns, null, null, null)
        if (cursor == null) {
            unavailable(context, "Provider returned no Watch Next table")
            false
        } else cursor.use { columns.all { name -> it.getColumnIndex(name) >= 0 } }.also {
            if (!it) unavailable(context, "Watch Next columns are unavailable")
        }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        unavailable(context, e.javaClass.simpleName)
        false
    }

    fun onFailure(context: Context, error: Exception): Boolean {
        if (error !is SecurityException && error !is IllegalArgumentException &&
            error !is UnsupportedOperationException && error !is SQLiteException) return false
        unavailable(context, error.javaClass.simpleName)
        return true
    }

    private fun unavailable(context: Context, reason: String) {
        val state = state(context)
        synchronized(state) {
            if (state.available == false) return
            state.available = false
            DebugLog.i("WatchNextManager", "Watch Next unavailable for this process: $reason")
        }
    }
}

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.hesier.lumy.ui.player.PlaybackStartupTiming.Measurement
import com.hesier.lumy.ui.player.PlaybackStartupTiming.Stage
import java.util.WeakHashMap

/** Only progressive sources carry our anonymous key; unkeyed HLS/subtitle reads are not guessed. */
internal class PlaybackStartupTimingTransfer(private val timing: PlaybackStartupTiming) : TransferListener {
    private class Transfer(val attempt: PlaybackStartupTiming.Attempt, val startedMs: Long) {
        var bytes = 0L
        var firstRead = true
        var http: PlaybackStartupHttp.Trace? = null
    }
    private val transfers = WeakHashMap<DataSource, Transfer>()
    private var cachedSource: DataSource? = null
    private var cachedTransfer: Transfer? = null
    private val http = PlaybackStartupHttp(timing)

    fun callFactory(client: okhttp3.OkHttpClient): okhttp3.Call.Factory = http.callFactory(client)

    @Synchronized override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        transfers.remove(source)
        cachedSource = source
        cachedTransfer = null
        http.clearPending()
        if (!isNetwork) return
        val attempt = timing.forKey(dataSpec.key) ?: return
        transfers[source] = Transfer(attempt, SystemClock.elapsedRealtime()).also {
            it.http = http.initializing(attempt, dataSpec)
            cachedTransfer = it
        }
    }

    @Synchronized override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        transfer(source)?.http?.opened()
    }

    @Synchronized override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec,
        isNetwork: Boolean, bytesTransferred: Int) {
        val transfer = transfer(source) ?: return
        if (bytesTransferred <= 0) return
        transfer.bytes += bytesTransferred
        if (!transfer.firstRead) return
        transfer.firstRead = false
        transfer.http?.firstRead(bytesTransferred)
        // First body read delivered to Media3, including open time; not server TTFB or socket timing.
        timing.mark(transfer.attempt, Stage.HTTP_FIRST_READ,
            Measurement(SystemClock.elapsedRealtime() - transfer.startedMs, bytesTransferred.toLong()))
    }

    @Synchronized override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        val transfer = transfers.remove(source) ?: return
        if (cachedSource === source) { cachedSource = null; cachedTransfer = null }
        transfer.http?.ended(transfer.bytes)
        timing.mark(transfer.attempt, Stage.HTTP_TRANSFER_END,
            Measurement(SystemClock.elapsedRealtime() - transfer.startedMs, transfer.bytes))
    }

    private fun transfer(source: DataSource): Transfer? {
        // EBML frequently reads one byte at a time; avoid weak-map lookups on that hot path.
        if (cachedSource !== source) {
            cachedSource = source
            cachedTransfer = transfers[source]
        }
        return cachedTransfer
    }
}

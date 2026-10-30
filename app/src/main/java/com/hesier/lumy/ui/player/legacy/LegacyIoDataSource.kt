@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player.legacy

import android.net.Uri
import androidx.annotation.Keep
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InterruptedIOException

/** FFmpeg's AVIO uses the same authenticated transport as the other extractors. */
@Keep
internal class LegacyIoDataSource(
    private val factory: DataSource.Factory,
    private val uri: Uri,
    private var length: Long,
) {
    private var source: DataSource? = null
    var position = 0L
        private set

    fun read(buffer: ByteArray, offset: Int, size: Int): Int {
        checkInterrupted()
        if (source == null) reopen(position)
        return try {
            val count = checkNotNull(source).read(buffer, offset, size)
            if (count > 0) position += count
            count
        } catch (error: IOException) {
            // A load retry must reopen the failed connection at its last successful byte.
            close()
            throw error
        }
    }

    fun seek(offset: Long, whence: Int): Long {
        checkInterrupted()
        if (whence == AVSEEK_SIZE) return length
        val target = when (whence and AVSEEK_FORCE.inv()) {
            SEEK_SET -> offset
            SEEK_CUR -> position + offset
            SEEK_END -> if (length != C.LENGTH_UNSET.toLong()) length + offset else return -1
            else -> return -1
        }
        if (target < 0 || (length != C.LENGTH_UNSET.toLong() && target > length)) return -1
        if (target != position || source == null) reopen(target)
        return position
    }

    fun close() {
        val previous = source
        source = null
        try {
            previous?.close()
        } catch (_: IOException) {
            // Preserve the read/seek error rather than replacing it with a close error.
        }
    }

    private fun reopen(target: Long) {
        close()
        checkInterrupted()
        val next = factory.createDataSource()
        source = next
        try {
            val remaining = next.open(DataSpec.Builder().setUri(uri).setPosition(target).build())
            if (remaining != C.LENGTH_UNSET.toLong()) length = target + remaining
            position = target
        } catch (error: IOException) {
            close()
            throw error
        }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
    }

    private companion object {
        const val SEEK_SET = 0
        const val SEEK_CUR = 1
        const val SEEK_END = 2
        const val AVSEEK_SIZE = 0x10000
        const val AVSEEK_FORCE = 0x20000
    }
}

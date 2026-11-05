@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/** Coalesces small HTTP reads above the underlying transfer listeners, without caching across opens. */
internal class BufferedHttpDataSource(private val upstream: DataSource) : DataSource by upstream {
    private val buffer = ByteArray(BUFFER_SIZE)
    private var position = 0
    private var limit = 0
    private var remaining = 0L

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun open(dataSpec: DataSpec): Long {
        reset()
        return try {
            upstream.open(dataSpec).also { length ->
                remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else length
            }
        } catch (failure: Exception) {
            reset()
            throw failure
        }
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > target.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        return try {
            if (position < limit) return copyTo(target, offset, length)
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            if (length >= BUFFER_SIZE) return readUpstream(target, offset, length)
            position = 0
            limit = readUpstream(buffer, 0, BUFFER_SIZE)
            if (limit <= 0) limit else copyTo(target, offset, length)
        } catch (failure: Exception) {
            reset()
            throw failure
        }
    }

    override fun close() {
        try { upstream.close() } finally { reset() }
    }

    private fun copyTo(target: ByteArray, offset: Int, length: Int): Int {
        val count = minOf(length, limit - position)
        System.arraycopy(buffer, position, target, offset, count)
        position += count
        return count
    }

    private fun readUpstream(target: ByteArray, offset: Int, length: Int): Int {
        val requested = if (remaining < 0) length else minOf(length.toLong(), remaining).toInt()
        return upstream.read(target, offset, requested).also { count ->
            if (count == C.RESULT_END_OF_INPUT) remaining = 0
            else if (remaining >= 0) remaining -= count
        }
    }

    private fun reset() { position = 0; limit = 0; remaining = 0 }

    private companion object { const val BUFFER_SIZE = 8 * 1024 }
}

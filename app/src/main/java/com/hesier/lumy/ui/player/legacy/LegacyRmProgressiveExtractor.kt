@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player.legacy

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.BundledExtractorsAdapter
import androidx.media3.exoplayer.source.ProgressiveMediaExtractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import java.io.InterruptedIOException

/** Sniff the container rather than relying on an extension on authenticated server URLs. */
class LegacyRmProgressiveExtractor private constructor(
    private val dataSources: DataSource.Factory,
    extractors: ExtractorsFactory,
) : ProgressiveMediaExtractor {
    class Factory(
        private val dataSources: DataSource.Factory,
        private val extractors: ExtractorsFactory,
    ) : ProgressiveMediaExtractor.Factory {
        override fun createProgressiveMediaExtractor(playerId: PlayerId): ProgressiveMediaExtractor =
            LegacyRmProgressiveExtractor(dataSources, extractors)
    }

    private val delegate = BundledExtractorsAdapter(extractors)
    private var rm: NativeRmDemuxer? = null
    private var ordinary = false

    override fun init(
        dataReader: DataReader,
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
        position: Long,
        length: Long,
        output: ExtractorOutput,
    ) {
        rm?.let {
            it.recoverIfNeeded()
            return
        }
        if (ordinary) {
            delegate.init(dataReader, uri, responseHeaders, position, length, output)
            return
        }
        val prefix = ByteArray(RM_MAGIC.size)
        var size = 0
        while (size < prefix.size) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            val count = dataReader.read(prefix, size, prefix.size - size)
            if (count == C.RESULT_END_OF_INPUT) break
            size += count
        }
        if (position == 0L && size == RM_MAGIC.size && prefix.contentEquals(RM_MAGIC)) {
            val demuxer = NativeRmDemuxer(LegacyIoDataSource(dataSources, uri, length), output)
            try {
                demuxer.open()
                rm = demuxer
            } catch (error: Throwable) {
                demuxer.release()
                throw error
            }
        } else {
            ordinary = true
            delegate.init(PrefixReader(prefix.copyOf(size), dataReader), uri, responseHeaders, position, length, output)
        }
    }

    override fun release() {
        rm?.release()
        rm = null
        delegate.release()
    }

    override fun disableSeekingOnMp3Streams() = delegate.disableSeekingOnMp3Streams()

    override fun getCurrentInputPosition() = rm?.position ?: delegate.currentInputPosition

    override fun seek(position: Long, seekTimeUs: Long) {
        rm?.let { it.seek(seekTimeUs) } ?: delegate.seek(position, seekTimeUs)
    }

    override fun read(positionHolder: PositionHolder): Int {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
        return rm?.read() ?: delegate.read(positionHolder)
    }

    private class PrefixReader(private val prefix: ByteArray, private val input: DataReader) : DataReader {
        private var position = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val size = minOf(length, prefix.size - position)
            if (size == 0) return input.read(buffer, offset, length)
            prefix.copyInto(buffer, offset, position, position + size)
            position += size
            return size
        }
    }

    private companion object {
        val RM_MAGIC = byteArrayOf('.'.code.toByte(), 'R'.code.toByte(), 'M'.code.toByte(), 'F'.code.toByte())
    }
}

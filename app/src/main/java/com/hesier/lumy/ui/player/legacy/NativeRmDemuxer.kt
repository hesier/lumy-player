@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player.legacy

import androidx.annotation.Keep
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.IndexSeekMap
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.IOException
import java.nio.ByteBuffer

@Keep
internal class NativeRmDemuxer(
    private val input: LegacyIoDataSource,
    private val output: ExtractorOutput,
) {
    private val tracks = mutableMapOf<Int, TrackOutput>()
    private var handle = 0L
    private var pendingSeekUs: Long? = null
    private var hasRead = false
    private var failed = false
    private val emittedTimes = mutableMapOf<Int, Long>()
    private val recoveryWatermarks = mutableMapOf<Int, Long>()
    val position: Long get() = input.position

    fun open() {
        try {
            if (!FfmpegLibrary.isAvailable()) throw IOException("FFmpeg library is unavailable")
            handle = nativeOpen(input)
            if (handle == 0L) throw IOException("Could not open RealMedia input")
        } catch (error: Throwable) {
            input.close()
            throw error
        }
    }

    fun seek(timeUs: Long) {
        // ProgressiveMediaPeriod issues an initial seek(0, 0) after opening the header.
        if (timeUs != 0L || hasRead) pendingSeekUs = timeUs
        emittedTimes.clear()
        recoveryWatermarks.clear()
    }

    fun read(): Int {
        try {
            pendingSeekUs?.let {
                nativeSeek(handle, it)
                pendingSeekUs = null
            }
            val result = nativeRead(handle)
            hasRead = true
            return result
        } catch (error: IOException) {
            failed = true
            throw error
        }
    }

    fun recoverIfNeeded() {
        if (!failed) return
        val resumeUs = pendingSeekUs ?: emittedTimes.values.maxOrNull() ?: 0L
        val previous = handle
        handle = 0
        try {
            if (previous != 0L) nativeRelease(previous)
        } finally {
            input.close()
        }
        input.seek(0, 0)
        open()
        pendingSeekUs = resumeUs
        recoveryWatermarks.clear()
        recoveryWatermarks.putAll(emittedTimes)
        failed = false
    }

    fun release() {
        val previous = handle
        handle = 0
        try {
            if (previous != 0L) nativeRelease(previous)
        } finally {
            input.close()
            tracks.clear()
            emittedTimes.clear()
            recoveryWatermarks.clear()
        }
    }

    @Suppress("unused")
    private fun onTrack(index: Int, mime: String, parameters: LongArray, extra: ByteArray, language: String?, label: String?) {
        val type = if (mime.startsWith("video/")) C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO
        val track = output.track(index, type)
        val csd = if (mime == "audio/cook") {
            // The audio extension needs both the original CSD and the container block size.
            listOf(extra, ByteBuffer.allocate(Int.SIZE_BYTES).putInt(parameters[5].toInt()).array())
        } else if (extra.isNotEmpty()) listOf(extra) else emptyList()
        track.format(Format.Builder().setId(index).setSampleMimeType(mime)
            .setCodecs(if (mime == "video/realvideo") "rv40" else null)
            .setWidth(parameters[0].toInt()).setHeight(parameters[1].toInt())
            .setChannelCount(parameters[2].toInt()).setSampleRate(parameters[3].toInt())
            .setAverageBitrate(parameters[4].toInt()).setInitializationData(csd)
            .setLanguage(language).setLabel(label).setSelectionFlags(parameters[6].toInt()).build())
        tracks[index] = track
    }

    @Suppress("unused")
    private fun onPrepared(durationUs: Long, seekable: Boolean, positions: LongArray, timesUs: LongArray) {
        if (tracks.isEmpty()) throw IOException("RealMedia contains no supported audio or video tracks")
        val seekMap = when {
            !seekable -> SeekMap.Unseekable(durationUs)
            positions.isNotEmpty() -> IndexSeekMap(positions, timesUs, durationUs)
            else -> object : SeekMap {
                override fun isSeekable() = true
                override fun getDurationUs() = durationUs
                // AVFormat performs its own binary/keyframe seek when an RM index is absent.
                override fun getSeekPoints(timeUs: Long) = SeekMap.SeekPoints(SeekPoint(timeUs, 0))
            }
        }
        output.seekMap(seekMap)
        output.endTracks()
    }

    @Suppress("unused")
    private fun onPacket(index: Int, data: ByteArray, timeUs: Long, keyFrame: Boolean) {
        val track = tracks[index] ?: return
        recoveryWatermarks[index]?.let { watermark ->
            if (timeUs <= watermark) return
            // B-frames may resume out of PTS order after this track crosses its old watermark.
            recoveryWatermarks.remove(index)
        }
        // Keep the complete FFmpeg packet, including RealVideo's slice-offset table.
        track.sampleData(ParsableByteArray(data), data.size)
        track.sampleMetadata(timeUs, if (keyFrame) C.BUFFER_FLAG_KEY_FRAME else 0, data.size, 0, null)
        emittedTimes[index] = maxOf(timeUs, emittedTimes[index] ?: Long.MIN_VALUE)
    }

    private external fun nativeOpen(input: LegacyIoDataSource): Long
    private external fun nativeRead(handle: Long): Int
    private external fun nativeSeek(handle: Long, timeUs: Long)
    private external fun nativeRelease(handle: Long)
}

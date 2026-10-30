@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player.legacy

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.extractor.DummyTrackOutput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.IOException

class LegacyRmRecoveryTest {
    fun runAll() {
        recoveryKeepsReorderedFrames()
        failedHeaderClosesTransport()
    }

    private fun recoveryKeepsReorderedFrames() {
        val input = LegacyIoDataSource(DataSource.Factory { ByteArrayDataSource(byteArrayOf(1)) }, URI, 1)
        val demuxer = NativeRmDemuxer(input, ExtractorOutput.PLACEHOLDER)
        val video = RecordingTrack()
        val audio = RecordingTrack()
        @Suppress("UNCHECKED_CAST")
        fun <T> field(name: String): T = NativeRmDemuxer::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(demuxer) as T
        val tracks: MutableMap<Int, TrackOutput> = field("tracks")
        tracks[0] = video
        tracks[1] = audio
        val packet = NativeRmDemuxer::class.java.getDeclaredMethod("onPacket", Int::class.javaPrimitiveType,
            ByteArray::class.java, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        fun emit(track: Int, timeUs: Long) { packet.invoke(demuxer, track, byteArrayOf(1), timeUs, false) }
        try {
            emit(0, 300)
            emit(0, 200)
            emit(1, 100)
            val watermarks: MutableMap<Int, Long> = field("recoveryWatermarks")
            watermarks.putAll(field<Map<Int, Long>>("emittedTimes"))
            emit(0, 290)
            emit(1, 80)
            emit(0, 310)
            emit(0, 305)
            emit(0, 295)
            emit(1, 90)
            check(video.times == listOf(300L, 200L, 310L, 305L, 295L)) { "Reordered video PTS was dropped" }
            check(audio.times == listOf(100L) && watermarks == mapOf(1 to 100L))
            emit(1, 110)
            emit(1, 105)
            check(audio.times == listOf(100L, 110L, 105L) && watermarks.isEmpty())
            watermarks[0] = 300
            demuxer.seek(0)
            emit(0, 10)
            check(video.times.last() == 10L && watermarks.isEmpty()) { "Seek retained recovery watermarks" }
        } finally {
            demuxer.release()
        }
    }

    private fun failedHeaderClosesTransport() {
        if (!FfmpegLibrary.isAvailable()) return
        var active = 0
        val factory = DataSource.Factory {
            val source = ByteArrayDataSource(".RMF malformed".toByteArray())
            object : DataSource by source {
                private var open = false
                override fun open(dataSpec: DataSpec): Long {
                    open = true
                    active++
                    return source.open(dataSpec)
                }
                override fun close() {
                    if (open) active--
                    open = false
                    source.close()
                }
            }
        }
        val demuxer = NativeRmDemuxer(LegacyIoDataSource(factory, URI, C.LENGTH_UNSET.toLong()),
            ExtractorOutput.PLACEHOLDER)
        try {
            check(runCatching { demuxer.open() }.exceptionOrNull() is IOException)
            check(active == 0) { "Failed native initialization left the transport open" }
        } finally {
            demuxer.release()
        }
    }

    private class RecordingTrack : TrackOutput by DummyTrackOutput() {
        val times = mutableListOf<Long>()
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            times.add(timeUs)
        }
    }

    private companion object {
        val URI: Uri = Uri.parse("https://example.invalid/rm")
    }
}

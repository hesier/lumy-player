@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CueEncoder
import java.io.ByteArrayOutputStream
import java.io.File

/** Optional device probe using a locally supplied SUP fixture, without network or playback. */
internal class PgsPerformanceProbe {
    fun run(directory: File): String {
        val data = File(directory, "pgs-benchmark.sup").readBytes()
        return listOf(false, true).joinToString("\n", postfix = "\n") { encode ->
            val parser = MultiObjectPgsParser()
            val encoder = CueEncoder()
            val display = ByteArrayOutputStream()
            var offset = 0
            var count = 0
            var outputBytes = 0L
            var encodeNs = 0L
            val start = SystemClock.elapsedRealtimeNanos()
            while (offset < data.size) {
                check(offset + 13 <= data.size && data[offset] == 0x50.toByte() && data[offset + 1] == 0x47.toByte())
                val type = data[offset + 10].toInt() and 0xff
                val size = ((data[offset + 11].toInt() and 0xff) shl 8) or (data[offset + 12].toInt() and 0xff)
                check(offset + 13 + size <= data.size)
                display.write(data, offset + 10, size + 3)
                offset += size + 13
                if (type == 0x80) {
                    val bytes = display.toByteArray()
                    parser.consume(bytes, 0, bytes.size, if (encode) Consumer {
                        val before = SystemClock.elapsedRealtimeNanos()
                        outputBytes += encoder.encode(it.cues, C.TIME_UNSET).size
                        encodeNs += SystemClock.elapsedRealtimeNanos() - before
                    } else null)
                    display.reset()
                    count++
                }
            }
            "encode=$encode displays=$count elapsedMs=${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000} " +
                "encodeMs=${encodeNs / 1_000_000} outputBytes=$outputBytes"
        }
    }
}

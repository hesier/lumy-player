@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player.legacy

import android.os.SystemClock
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.Format
import java.io.File
import org.json.JSONObject

/** The fixture remains private; exercise RV40 software fallback on a device that also has hardware. */
class SoftwareRv40FixtureTest {
    fun runFixture(path: String) {
        val fixture = JSONObject(File(path).readText())
        check(fixture.getString("codec_name") == "rv40")
        val packets = fixture.getJSONArray("packets")
        val format = Format.Builder().setSampleMimeType("video/realvideo").setCodecs("rv40")
            .setWidth(fixture.getInt("width")).setHeight(fixture.getInt("height"))
            .setInitializationData(listOf(Base64.decode(fixture.getString("csd"), Base64.DEFAULT))).build()
        val decoder = SoftwareVideoDecoder(format)
        var sent = 0
        var frames = 0
        var ended = false
        val times = mutableSetOf<Long>()
        val deadline = SystemClock.elapsedRealtime() + 30_000
        try {
            while (!ended && SystemClock.elapsedRealtime() < deadline) {
                if (sent <= packets.length()) decoder.dequeueInputBuffer()?.let { input ->
                    if (sent == packets.length()) input.addFlag(C.BUFFER_FLAG_END_OF_STREAM) else {
                        val packet = packets.getJSONObject(sent)
                        val data = Base64.decode(packet.getString("base64"), Base64.DEFAULT)
                        input.ensureSpaceForWrite(data.size)
                        checkNotNull(input.data).put(data)
                        input.timeUs = packet.getLong("ptsUs")
                        input.flip()
                    }
                    decoder.queueInputBuffer(input)
                    sent++
                }
                val output = decoder.dequeueOutputBuffer()
                if (output == null) { Thread.sleep(1); continue }
                try {
                    ended = output.isEndOfStream
                    if (!ended) {
                        val data = checkNotNull(output.data)
                        check(data.remaining() == format.width * format.height * 4)
                        check((data.get(3).toInt() and 255) == 255)
                        frames++
                        times.add(output.timeUs)
                    }
                } finally { output.release() }
            }
            check(ended && frames == packets.length() && times.size > 10) {
                "RV40 software drain failed: frames=$frames, packets=${packets.length()}, EOS=$ended"
            }
        } finally { decoder.release() }
    }
}

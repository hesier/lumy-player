@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.hesier.lumy.R
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.PlaybackMode
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.databinding.ActivityPlayerBinding
import com.hesier.lumy.ui.ThemedActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class PlayerInfo(
    private val activity: ThemedActivity,
    private val binding: ActivityPlayerBinding,
    private val player: ExoPlayer,
    private val selection: PlaybackSelection,
    private val server: () -> MediaServer,
) {
    var videoDecoder: String? = null
    var audioDecoder: String? = null
    var passthroughEncoding: Int? = null
    var droppedFrames = 0
    val visible get() = binding.info.visibility == View.VISIBLE

    private var infoJob: Job? = null

    fun toggle() {
        if (binding.info.visibility == View.VISIBLE) {
            infoJob?.cancel()
            binding.info.visibility = View.GONE
            return
        }
        binding.info.visibility = View.VISIBLE
        infoJob = activity.lifecycleScope.launch {
            while (isActive) {
                binding.info.text = infoText()
                delay(1_000)
            }
        }
    }

    private fun infoText(): String {
        val p = selection.plan
        val modeLabel = when (val m = p?.mode) {
            PlaybackMode.Direct -> activity.getString(R.string.mode_direct)
            is PlaybackMode.Transcode -> if (m.copyVideo && server().session.kind != ServerKind.FNOS) {
                activity.getString(R.string.mode_direct_stream)
            } else {
                activity.getString(R.string.mode_transcode)
            }
            null -> "-"
        }
        val v = player.videoFormat
        val a = player.audioFormat
        val yesNo = { b: Boolean -> activity.getString(if (b) R.string.supported else R.string.unsupported) }
        val rows = buildList {
            add(R.string.info_mode to modeLabel)
            p?.reasons?.takeIf { it.isNotEmpty() }?.let { add(R.string.info_reasons to it.text(activity)) }
            add(R.string.info_source to "${selection.version.summary}  ${selection.version.bitrateKbps?.let { "%.1f Mbps".format(it / 1000f) } ?: ""}")
            if (v != null) add(R.string.info_video to "${v.width}x${v.height} ${v.sampleMimeType} ${v.codecs ?: ""} ${if (v.frameRate > 0) "%.3f fps".format(v.frameRate) else ""}")
            add(R.string.info_video_decoder to (videoDecoder ?: "-"))
            if (a != null) add(R.string.info_audio to "${a.sampleMimeType} ${a.channelCount}ch ${a.sampleRate}Hz")
            add(R.string.info_audio_decoder to (passthroughEncoding?.let { activity.getString(R.string.info_passthrough, encodingName(it)) } ?: audioDecoder ?: "-"))
            add(R.string.info_dropped_frames to droppedFrames.toString())
            add(R.string.info_buffer to activity.getString(R.string.info_seconds, (player.bufferedPosition - player.currentPosition) / 1000f))
            add(R.string.info_speed to "${player.playbackParameters.speed}x")
            add(R.string.info_hdr to activity.getString(R.string.info_hdr_value, yesNo(selection.device.displaySupportsHdr), yesNo(selection.settings.displaySupportsHdr)))
        }.map { (label, value) -> activity.getString(label) to value }
        // CJK glyphs occupy two columns in the monospace info panel.
        val width = rows.maxOf { monoWidth(it.first) } + 2
        return rows.joinToString("\n") { (label, value) -> label + " ".repeat(width - monoWidth(label)) + value }
    }

    private fun monoWidth(s: String): Int = s.sumOf { if (it.code >= 0x2E80) 2 else 1 }

    private fun encodingName(encoding: Int) = when (encoding) {
        C.ENCODING_AC3 -> "AC3"
        C.ENCODING_E_AC3 -> "E-AC3"
        C.ENCODING_E_AC3_JOC -> "E-AC3 Atmos"
        C.ENCODING_DTS -> "DTS"
        C.ENCODING_DTS_HD -> "DTS-HD"
        C.ENCODING_DOLBY_TRUEHD -> "TrueHD"
        else -> "encoding $encoding"
    }

    fun release() { infoJob?.cancel() }
}

@file:OptIn(UnstableApi::class)

package com.hesier.lumy.ui.settings

import android.app.Activity
import android.content.res.Resources
import android.graphics.Point
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.Display
import androidx.core.text.inSpans
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.ui.player.DeviceProfile
import java.util.Locale

/**
 * 设置 →「关于」里的本机信息：只列和播放有关的能力，方便用户自己判断为什么要转码，也方便报问题时拍照。
 * 查询解码器较慢，在后台线程调用。
 */
object DeviceInfo {

    fun build(activity: Activity): CharSequence {
        val profile = DeviceProfile(activity)
        val out = SpannableStringBuilder()
        listOf(
            R.string.device_info_device to device(activity),
            R.string.device_info_display to display(activity),
            R.string.device_info_video to video(activity, profile),
            R.string.device_info_audio to audio(activity),
        ).forEach { (title, lines) ->
            if (out.isNotEmpty()) out.append("\n\n")
            // 分组标题加粗，和下面的条目区分开
            out.inSpans(StyleSpan(Typeface.BOLD)) { append(activity.getString(title)) }
            lines.forEach { out.append('\n').append(it) }
        }
        return out
    }

    private fun device(a: Activity) = listOf(
        a.getString(R.string.device_info_model, "${Build.MANUFACTURER} ${Build.MODEL}"),
        a.getString(R.string.device_info_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
        a.getString(R.string.device_info_abi, Build.SUPPORTED_ABIS.joinToString(a.getString(R.string.device_info_separator))),
    )

    private fun display(a: Activity): List<String> {
        @Suppress("DEPRECATION")
        val display = a.windowManager.defaultDisplay
        val lines = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val mode = display.mode
            lines += a.getString(R.string.device_info_resolution, "${mode.physicalWidth}×${mode.physicalHeight} @ ${hz(mode.refreshRate)} Hz")
            // 只列和当前分辨率相同的模式：匹配帧率时不会切换分辨率
            val rates = display.supportedModes
                .filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
                .map { it.refreshRate }.distinct().sorted()
            lines += a.getString(R.string.device_info_refresh_rates, rates.joinToString(a.getString(R.string.device_info_separator)) { "${hz(it)} Hz" })
        } else {
            val size = Point().also { display.getRealSize(it) }
            lines += a.getString(R.string.device_info_resolution, "${size.x}×${size.y}")
        }
        // 用系统的值，不受界面大小设置影响
        lines += a.getString(R.string.device_info_density, Resources.getSystem().displayMetrics.densityDpi)
        lines += a.getString(R.string.device_info_hdr, hdrTypes(a, display))
        return lines
    }

    private fun hdrTypes(a: Activity, display: Display): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return a.getString(R.string.device_info_unsupported)
        val names = display.hdrCapabilities?.supportedHdrTypes?.sorted().orEmpty().mapNotNull {
            when (it) {
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> a.getString(R.string.device_info_dolby_vision)
                else -> null
            }
        }
        return if (names.isEmpty()) a.getString(R.string.device_info_unsupported)
        else names.joinToString(a.getString(R.string.device_info_separator))
    }

    private fun video(a: Activity, profile: DeviceProfile): List<String> {
        val lines = VIDEO_CODECS.map { (name, mime) ->
            var value = a.getString(
                when {
                    profile.hasHardwareDecoder(mime) -> R.string.device_info_hardware
                    profile.hasDecoder(mime) -> R.string.device_info_software
                    else -> R.string.device_info_unsupported
                },
            )
            if (mime in TEN_BIT_CODECS && profile.supportsTenBit(mime)) value = a.getString(R.string.device_info_ten_bit, value)
            a.getString(R.string.device_info_item, name, value)
        }.toMutableList()
        lines += a.getString(
            R.string.device_info_item,
            a.getString(R.string.device_info_dolby_vision),
            a.getString(if (profile.hasDolbyVisionDecoder()) R.string.device_info_hardware else R.string.device_info_unsupported),
        )
        // 播放时自动检测到解不出画面、之后改用 HEVC 基础层的 profile
        val broken = LumyApp.instance.settings.brokenDolbyVisionProfiles
        if (broken.isNotEmpty()) {
            lines += a.getString(R.string.device_info_dovi_base_layer, broken.sorted().joinToString(a.getString(R.string.device_info_separator)) { "P$it" })
        }
        return lines
    }

    /** 每次打开都重新读：换了电视或功放能力就不同 */
    private fun audio(a: Activity): List<String> {
        val caps = AudioCapabilities.getCapabilities(a, AudioAttributes.DEFAULT, null)
        val supported = AUDIO_ENCODINGS.filter { caps.supportsEncoding(it.second) }.map { it.first }
        val value = if (supported.isEmpty()) a.getString(R.string.device_info_unsupported)
        else supported.joinToString(a.getString(R.string.device_info_separator))
        return listOf(value, a.getString(R.string.device_info_audio_note))
    }

    private fun hz(rate: Float): String =
        String.format(Locale.US, "%.3f", rate).trimEnd('0').trimEnd('.')

    private val VIDEO_CODECS = listOf(
        "H.264" to MimeTypes.VIDEO_H264,
        "HEVC" to MimeTypes.VIDEO_H265,
        "VP9" to MimeTypes.VIDEO_VP9,
        "AV1" to MimeTypes.VIDEO_AV1,
        "MPEG-2" to MimeTypes.VIDEO_MPEG2,
        "VC-1" to "video/wvc1",
    )
    private val TEN_BIT_CODECS = setOf(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_VP9, MimeTypes.VIDEO_AV1)

    private val AUDIO_ENCODINGS = listOf(
        "AC3" to C.ENCODING_AC3,
        "E-AC3" to C.ENCODING_E_AC3,
        "E-AC3 JOC" to C.ENCODING_E_AC3_JOC,
        "DTS" to C.ENCODING_DTS,
        "DTS-HD" to C.ENCODING_DTS_HD,
        "TrueHD" to C.ENCODING_DOLBY_TRUEHD,
    )
}

package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import com.hesier.lumy.core.MediaPart
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.PlaybackMode
import com.hesier.lumy.core.SubtitleStream
import com.hesier.lumy.databinding.ActivityPlayerBinding
import com.hesier.lumy.ui.settings.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class PlaybackNoticeTest(private val instrumentation: Instrumentation) {
    private val context get() = instrumentation.targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun runAll() {
        val activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        lateinit var binding: ActivityPlayerBinding
        lateinit var focus: Button
        try {
            instrumentation.runOnMainSync {
                checkReasons()
                binding = ActivityPlayerBinding.inflate(activity.layoutInflater)
                activity.setContentView(binding.root)
                binding.buffering.visibility = View.GONE
                disableFocus(binding.root)
                focus = Button(activity).apply { isFocusableInTouchMode = true }
                binding.root.addView(focus, android.widget.FrameLayout.LayoutParams(100, 60))
                focus.requestFocus()
                checkNotices(PlaybackNotice(binding.playbackNotice, scope), binding)
            }
            instrumentation.waitForIdleSync()
            for (key in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)) {
                instrumentation.sendKeyDownUpSync(key)
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { check(focus.hasFocus() && !binding.playbackNotice.isFocusable) { "Notice changed focus for key $key" } }
            }
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            java.io.File(context.cacheDir, "playback-notice-test.png").outputStream().use {
                check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            screenshot.recycle()
            SystemClock.sleep(6500)
            instrumentation.runOnMainSync { check(binding.playbackNotice.visibility == View.GONE) }
        } finally {
            scope.cancel()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun disableFocus(view: View) {
        view.isFocusable = false
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) disableFocus(view.getChildAt(index))
        }
    }

    private fun checkReasons() {
        val planner = PlaybackPlanner(DeviceProfile(context))
        val source = MediaVersion("v", 0, "mp4", 26_000, null, emptyList(), emptyList(), emptyList())
        val policy = PlanPolicy(true, true, maxTotalBitrateKbps = 7500)
        fun plan(v: MediaVersion = source, reason: PlaybackIssue? = null) = planner.plan(
            v, null, null, Quality.Original, policy, forceTranscode = reason != null, fallbackReason = reason,
        )
        check(plan().reasons == listOf(PlaybackIssue.BandwidthLimit(7500, 26_000)))
        check(plan(source.copy(bitrateKbps = null)).reasons == listOf(PlaybackIssue.BandwidthLimit(7500)))
        val partial = source.copy(bitrateKbps = 1000,
            parts = listOf(MediaPart("a", "a", null, 2_000_000, 1000), MediaPart("b", "b", null, null, null)))
        check(plan(partial).reasons == listOf(PlaybackIssue.BandwidthLimit(7500, 16_000)))
        check(plan(source.copy(bitrateKbps = 1000), PlaybackIssue.Unseekable).reasons == listOf(PlaybackIssue.Unseekable))
        val manual = planner.plan(source.copy(bitrateKbps = 1000), null, null, Quality.Limited(720, 4000), policy)
        check(manual.reasons == listOf(PlaybackIssue.QualityLimit(720, 4000)))
        val subtitle = SubtitleStream("s", codec = "srt", language = null, title = "s", forced = false, selected = true, key = "/srt")
        val burn = planner.plan(source.copy(bitrateKbps = 1000), null, subtitle, Quality.Original, policy,
            forceTranscode = true, fallbackReason = PlaybackIssue.SubtitleNeedsBurn("srt"))
        check(burn.reasons == listOf(PlaybackIssue.SubtitleNeedsBurn("srt")))
        check((burn.mode as PlaybackMode.Transcode).burnSubtitleId == "s")
        val failed = planner.plan(source.copy(bitrateKbps = 1000), null, null, Quality.Original, policy,
            forceTranscode = true, videoDecodeFailed = true, fallbackReason = PlaybackIssue.Unseekable)
        check(failed.reasons == listOf(PlaybackIssue.VideoDecodeFailed))
    }

    private fun checkNotices(notice: PlaybackNotice, binding: ActivityPlayerBinding) {
        val uiContext = binding.playbackNotice.context
        val reasons = listOf(
            PlaybackIssue.BandwidthLimit(7500, 26_000), PlaybackIssue.BandwidthLimit(7500),
            PlaybackIssue.QualityLimit(1080, 12_000), PlaybackIssue.OutputBitrateLimit(7500),
            PlaybackIssue.VideoDecodeFailed, PlaybackIssue.DirectPlayFailed, PlaybackIssue.Unseekable,
            PlaybackIssue.Container("avi"), PlaybackIssue.UnknownVideoCodec("x"), PlaybackIssue.NoVideoDecoder("hevc"),
            PlaybackIssue.VideoTooDemanding("3840x2160", false, "hevc"),
            PlaybackIssue.VideoTooDemanding("3840x2160", true, "hevc"),
            PlaybackIssue.UnknownAudioCodec("x"), PlaybackIssue.NoAudioDecoder("dts"),
            PlaybackIssue.HdrUnsupported, PlaybackIssue.SubtitleNeedsBurn("pgs"),
        )
        for ((index, reason) in reasons.withIndex()) {
            val text = reason.text(uiContext)
            check(text.isNotBlank() && !text.contains("%") && !text.contains("或未知"))
            notice.show(index.toString(), Plan(PlaybackMode.Transcode(copyVideo = false), listOf(reason)))
            check(binding.playbackNotice.text.contains(text))
        }
        val multiple = Plan(PlaybackMode.Transcode(copyVideo = false), listOf(reasons[0], reasons[11]))
        notice.show("multiple", multiple)
        val failed = uiContext.getString(com.hesier.lumy.R.string.player_server_playback_error, "HTTP 403", multiple.reasons.text(uiContext))
        check(failed.contains("HTTP 403") && failed.contains(multiple.reasons.text(uiContext)))
        val precise = PlaybackIssue.BandwidthLimit(7500, 7501).text(uiContext)
        check(precise.contains("7.501") && precise.contains("7.50"))
        val original = binding.playbackNotice.text.toString()
        binding.playbackNotice.text = "deduplication marker"
        notice.show("multiple", multiple)
        check(binding.playbackNotice.text == "deduplication marker")
        notice.show("multiple", Plan(PlaybackMode.Direct, emptyList()))
        check(binding.playbackNotice.visibility == View.GONE)
        notice.show("multiple", multiple)
        check(binding.playbackNotice.text.toString() == original)
        notice.show("stream", Plan(PlaybackMode.Transcode(), listOf(PlaybackIssue.Container("x"))))
        check(binding.playbackNotice.text.contains(uiContext.getString(com.hesier.lumy.R.string.player_stream_notice, "")))
        notice.show("multiple", multiple)
        check(binding.playbackNotice.visibility == View.VISIBLE)
    }
}

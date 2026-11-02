package com.hesier.lumy.ui.player

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.PlaybackMode
import com.hesier.lumy.data.Settings

class BandwidthSettingsTest {
    fun runAll(context: Context) {
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("bandwidth-test-$name", mode)
        }
        val prefs = isolated.getSharedPreferences("settings", Context.MODE_PRIVATE)
        check(prefs.edit().clear().commit())
        try {
            checkPreferences(isolated, prefs)
            checkPlanning(isolated)
        } finally {
            check(prefs.edit().clear().commit())
        }
    }

    private fun checkPreferences(context: Context, prefs: SharedPreferences) {
        val settings = Settings(context)
        check(settings.allowTranscode("a") && settings.bandwidthMbps("a") == 0)
        check(prefs.edit().putBoolean("allow_transcode", false).commit())
        check(!settings.allowTranscode("a") && !settings.allowTranscode("b"))
        settings.setAllowTranscode("a", true)
        settings.setBandwidthMbps("a", 20)
        val restored = Settings(context)
        check(restored.allowTranscode("a") && !restored.allowTranscode("b"))
        check(restored.bandwidthMbps("a") == 20 && restored.bandwidthMbps("b") == 0)
        restored.setBandwidthMbps("b", 100)
        restored.forgetServer("a")
        check(restored.bandwidthMbps("a") == 0 && !restored.allowTranscode("a"))
        check(restored.bandwidthMbps("b") == 100 && !restored.allowTranscode("b"))
    }

    private fun checkPlanning(context: Context) {
        val planner = PlaybackPlanner(DeviceProfile(context))
        val version = MediaVersion("v", 0, "mp4", 20_000, null, emptyList(), emptyList(), emptyList())
        val policy = PlanPolicy(true, false, maxTotalBitrateKbps = 15_000)
        val over = planner.plan(version, null, null, Quality.Original, policy)
        check((over.mode as PlaybackMode.Transcode).let { !it.copyVideo && it.maxTotalBitrateKbps == 15_000 })
        check(over.reasons.contains(PlaybackIssue.BandwidthLimit(15_000, 20_000)))
        for (rate in listOf(10_000, 15_000)) {
            check(planner.plan(version.copy(bitrateKbps = rate), null, null, Quality.Original, policy).mode == PlaybackMode.Direct)
        }
        check(planner.plan(version.copy(bitrateKbps = null), null, null, Quality.Original, policy).mode is PlaybackMode.Transcode)
        val disabled = planner.plan(version, null, null, Quality.Original, policy.copy(allowTranscode = false))
        check(disabled.mode == PlaybackMode.Direct && disabled.reasons.isNotEmpty())
        check(planner.plan(version, null, null, Quality.Original, policy.copy(maxTotalBitrateKbps = null)).mode == PlaybackMode.Direct)
        val unsupportedAudio = com.hesier.lumy.core.AudioStream(
            id = "a", codec = "unknown-audio", channels = 2, language = null, title = "Audio", selected = true, bitrateKbps = 128,
        )
        val expanded = planner.plan(version.copy(bitrateKbps = 1400, audio = listOf(unsupportedAudio)),
            unsupportedAudio, null, Quality.Original, policy.copy(maxTotalBitrateKbps = 1500)).mode as PlaybackMode.Transcode
        check(!expanded.copyVideo)
        val manual = planner.plan(version, null, null, Quality.Limited(720, 4000), policy).mode as PlaybackMode.Transcode
        check(manual.maxBitrateKbps == 4000 && manual.maxTotalBitrateKbps == 15_000 && !manual.copyVideo)
    }
}

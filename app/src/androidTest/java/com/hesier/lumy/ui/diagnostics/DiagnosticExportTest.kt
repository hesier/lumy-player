package com.hesier.lumy.ui.diagnostics

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hesier.lumy.R
import com.hesier.lumy.net.BackupTransferServer
import com.hesier.lumy.net.DiagnosticJournal.Event
import com.hesier.lumy.net.DiagnosticJournal.Label
import com.hesier.lumy.net.DiagnosticJournal.Metric
import com.hesier.lumy.ui.settings.SettingsActivity
import com.hesier.lumy.util.diagnostics.PlaybackDiagnostics
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Exercises the real settings entry and LAN endpoint without a media server account. */
class DiagnosticExportTest(private val instrumentation: Instrumentation) {
    private lateinit var activity: SettingsActivity

    fun runAll(): String {
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as SettingsActivity
        try {
            await { main { activity.hasWindowFocus() } }
            focus(R.string.settings_section_about)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            focus(R.string.diagnostics_export)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await { main { !activity.hasWindowFocus() } }
            // The only actionable control must initially be Cancel.
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await { main { activity.hasWindowFocus() } }
            check(main { contains(activity.currentFocus, activity.getString(R.string.diagnostics_export)) })
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await { main { !activity.hasWindowFocus() } }
            key(KeyEvent.KEYCODE_BACK)
            await { main { activity.hasWindowFocus() } }
            check(main { contains(activity.currentFocus, activity.getString(R.string.diagnostics_export)) })
            checkDownload()
            return "Diagnostics: settings D-pad entry, Cancel/Back focus restoration, token download and JSON privacy passed"
        } finally { main { activity.finishAndRemoveTask() } }
    }

    private fun checkDownload() {
        PlaybackDiagnostics.record(Event.ERROR, mapOf(Metric.ERROR_CODE to 4001), mapOf(Label.CONTAINER to "private-title.mkv"))
        PlaybackDiagnostics.record(Event.STOP, mapOf(Metric.POSITION_MS to 12345))
        val panel = main { DiagnosticTransferPanel(activity).also { it.show() } }
        try {
            var server: BackupTransferServer? = null
            await { server = main {
                panel.javaClass.getDeclaredField("server").apply { isAccessible = true }.get(panel) as? BackupTransferServer
            }; server != null }
            val uri = URI(checkNotNull(server).url)
            val connection = URL("http://${uri.authority}/backup").openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.setRequestProperty("X-Lumy-Token", uri.fragment)
            try {
                check(connection.responseCode == 200)
                check(connection.getHeaderField("Content-Disposition").contains("Lumy-diagnostics.json"))
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                listOf("schemaVersion", "appVersion", "androidSdk", "androidRelease", "manufacturer", "model", "events", "serverKind", "videoCapabilities", "allowSoftwareVideoDecoding", "displaySupportsHdr", "hdrEnabled").forEach { check(json.has(it)) }
                check(json.getString("serverKind") in setOf("NONE", "PLEX", "JELLYFIN", "EMBY", "FNOS"))
                val capabilities = json.getJSONObject("videoCapabilities")
                check(capabilities.keys().asSequence().toSet() == setOf("h264", "hevc", "av1", "vp9"))
                listOf("h264", "hevc", "av1", "vp9").forEach { codec ->
                    val entry = capabilities.getJSONObject(codec)
                    listOf("hasDecoder", "hasHardwareDecoder", "supportsTenBit").forEach { check(entry.get(it) is Boolean) }
                }
                check(text.contains("ERROR_CODE") && text.contains("4001") && text.contains("STOP"))
                check(!text.contains("private-title.mkv"))
                check(!text.contains(uri.fragment))
            } finally { connection.disconnect() }
        } finally { main { panel.dismiss() } }
    }

    private fun focus(label: Int) {
        repeat(24) {
            if (main { contains(activity.currentFocus, activity.getString(label)) }) return
            key(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        error("D-pad could not reach ${activity.getString(label)}")
    }

    private fun contains(view: View?, text: String): Boolean = when (view) {
        is TextView -> view.text.toString() == text
        is ViewGroup -> (0 until view.childCount).any { contains(view.getChildAt(it), text) }
        else -> false
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        SystemClock.sleep(180)
        instrumentation.waitForIdleSync()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 6000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for diagnostic export" }
            SystemClock.sleep(40)
        }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}

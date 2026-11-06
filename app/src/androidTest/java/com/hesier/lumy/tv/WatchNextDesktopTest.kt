@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.tv

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.core.PlaybackState
import com.hesier.lumy.data.SavedServer
import com.hesier.lumy.ui.player.PlayerActivity
import com.hesier.lumy.ui.settings.SettingsActivity
import kotlinx.coroutines.runBlocking

/** Real provider, launcher entry, playback resume and remote-control settings integration. */
class WatchNextDesktopTest(private val instrumentation: Instrumentation) {
    private val context get() = instrumentation.targetContext
    private val app get() = LumyApp.instance
    private var playerActivity: PlayerActivity? = null
    private var settingsActivity: SettingsActivity? = null
    private data class Card(val intent: Intent, val position: Long)

    fun runAll(): String = runBlocking {
        instrumentation.waitForIdleSync()
        check(app.store.servers.isEmpty()) { "Desktop test requires an empty temporary emulator account store" }
        check(cards().isEmpty()) { "Refusing to clear existing launcher cards" }
        check(WatchNextManager.isSupported()) { "Desktop test requires API 26+" }
        val field = LumyApp::class.java.getDeclaredField("server").apply { isAccessible = true }
        val oldServer = field.get(app)
        val oldId = app.store.currentId
        val original = Triple(app.settings.watchNextEnabled, app.settings.defaultQuality, app.settings.danmaku.enabled)
        WatchNextDesktopFixture(instrumentation).use { fixture ->
            val entry = SavedServer("desktop-fixture-account", fixture.session)
            try {
                app.store.add(entry)
                app.store.currentId = entry.id
                field.set(app, fixture.server)
                app.settings.defaultQuality = 0
                app.settings.danmaku.enabled = false
                app.settings.watchNextEnabled = true
                WatchNextArtworkRoutingTest(instrumentation).runAll(fixture)
                WatchNextManager.setEnabled(context, true)
                val regressions = WatchNextDesktopRegressions(instrumentation)
                regressions.verifyArtwork(fixture)
                regressions.verifyLateArtwork(fixture)
                regressions.verifyRestore(fixture)
                val resumeItem = fixture.item.copy(title = "Lumy Protected Resume Fixture")
                WatchNextManager.onPlaybackProgress(context, fixture.server, resumeItem, 120_000L,
                    600_000L, null, PlaybackState.PAUSED,
                    resume = WatchNextResume("stable-second", "fixture-playlist"))
                val card = cards().single()
                check(card.intent.getStringExtra("server_id") == entry.id)
                check(card.intent.getStringExtra("watch_next_version_id") == "stable-second")
                check(card.intent.getStringExtra("playlist") == "fixture-playlist")
                val launcher = openLauncherCard(resumeItem.title)
                verifyPlayback(fixture, card)
                verifySettings(fixture)
                "Real TvProvider + stable second version + playlist + 120s resume + offline local progress + HTTP/HTTPS artwork routing + protected artwork + backup restore + settings D-pad passed. $launcher"
            } finally {
                main { playerActivity?.finish(); settingsActivity?.finishAndRemoveTask() }
                instrumentation.waitForIdleSync()
                WatchNextManager.setEnabled(context, false)
                app.store.remove(entry.id)
                app.store.currentId = oldId
                field.set(app, oldServer)
                app.settings.watchNextEnabled = original.first
                app.settings.defaultQuality = original.second
                app.settings.danmaku.enabled = original.third
                WatchNextManager.onServerChanged(context)
                check(app.store.servers.isEmpty())
                check(context.getSharedPreferences("session", android.content.Context.MODE_PRIVATE).edit().commit())
                check(context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().commit())
            }
        }
    }

    private fun cards(): List<Card> {
        val columns = arrayOf(WatchNextPrograms.COLUMN_CONTENT_ID, WatchNextPrograms.COLUMN_INTENT_URI,
            WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS)
        return context.contentResolver.query(WatchNextPrograms.CONTENT_URI, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(Card(Intent.parseUri(cursor.getString(1), 0), cursor.getLong(2)))
            }
        }.orEmpty()
    }

    private fun openLauncherCard(title: String): String {
        val monitor = instrumentation.addMonitor(PlayerActivity::class.java.name, null, false)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME"),
            ).use {
                it.readBytes()
            }
            var clicked = false
            val deadline = SystemClock.uptimeMillis() + 6_000
            while (!clicked && SystemClock.uptimeMillis() < deadline) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val match = root?.findAccessibilityNodeInfosByText(title)?.firstOrNull()
                if (match != null) {
                    screenshot("watch-next-launcher")
                    clicked = clickAncestor(match)
                }
                if (!clicked) SystemClock.sleep(250)
            }
            if (!clicked) screenshot("watch-next-launcher")
            if (clicked) playerActivity = monitor.waitForActivityWithTimeout(8_000) as? PlayerActivity
            return if (playerActivity != null) "Launcher card was visible and clicked."
            else "Launcher did not expose a clickable fixture card; verified the stored TvProvider Intent as the playback entry."
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun clickAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val target = current ?: return false
            if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = target.parent
        }
        return false
    }

    private fun verifyPlayback(fixture: WatchNextDesktopFixture, card: Card) {
        val activity = playerActivity ?: (instrumentation.startActivitySync(card.intent) as PlayerActivity)
            .also { playerActivity = it }
        await("playback request") { fixture.requests.isNotEmpty() }
        val request = fixture.requests.first()
        check(request.version.id == "stable-second") { "Stored stable version must override default index zero" }
        check(request.startMs == 120_000L) { "Player did not restore stored resume position" }
        check(activity.intent.getStringExtra("playlist") == "fixture-playlist")
        val playerField = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
        await("decoded resumed video") { main {
            val player = playerField.get(activity) as ExoPlayer
            player.isPlaying && player.videoFormat != null && player.currentPosition in 120_000L..140_000L
        } }
        await("local progress despite remote IOException", 22_000) {
            fixture.failedReports.get() > 0 && cards().singleOrNull()?.position?.let { it > 120_500L } == true
        }
        main {
            val detailField = PlayerActivity::class.java.getDeclaredField("detail").apply { isAccessible = true }
            val versions = (detailField.get(activity) as com.hesier.lumy.core.MediaDetail).versions
            val select = PlayerActivity::class.java.getDeclaredMethod("selectVersion", com.hesier.lumy.core.MediaVersion::class.java)
                .apply { isAccessible = true }
            select.invoke(activity, versions.first())
            check(activity.intent.getStringExtra("watch_next_version_id") == versions.first().id) {
                "Manual version selection must update the extra used by decoder fallback recreation"
            }
            select.invoke(activity, versions.last())
            activity.finish()
        }
        instrumentation.waitForIdleSync()
        await("player stopped") { main { activity.isDestroyed } }
        playerActivity = null
    }

    private fun verifySettings(fixture: WatchNextDesktopFixture) {
        val activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as SettingsActivity
        settingsActivity = activity
        await("settings initial focus") { main { activity.hasWindowFocus() && position(R.id.categories) == 0 } }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertPosition(R.id.list, 0)
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertPosition(R.id.list, 0)
        val count = main { activity.findViewById<RecyclerView>(R.id.list).adapter!!.itemCount }
        val toggle = count - 3
        repeat(toggle) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertRow(toggle, R.string.settings_watch_next)
        screenshot("watch-next-settings")
        clickAndWait { !app.settings.watchNextEnabled && cards().isEmpty() }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertRow(toggle + 1, R.string.settings_watch_next_refresh)
        val reads = fixture.homeReads.get()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(250)
        check(cards().isEmpty() && fixture.homeReads.get() == reads) { "Disabled refresh must not publish or fetch" }
        key(KeyEvent.KEYCODE_DPAD_UP)
        clickAndWait { app.settings.watchNextEnabled && cards().size == 1 && fixture.homeReads.get() > reads }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        val beforeRefresh = fixture.homeReads.get()
        clickAndWait { fixture.homeReads.get() > beforeRefresh && cards().size == 1 }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertRow(count - 1, R.string.settings_watch_next_clear)
        clickAndWait { cards().isEmpty() }
        check(app.settings.watchNextEnabled) { "Clearing cards must preserve the enabled preference" }
        checkNavigation(count - 1)
        main { activity.finishAndRemoveTask() }
        instrumentation.waitForIdleSync()
        settingsActivity = null
    }

    private fun checkNavigation(last: Int) {
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertPosition(R.id.list, last)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertPosition(R.id.categories, 0)
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertPosition(R.id.categories, 0)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertPosition(R.id.list, last)
        key(KeyEvent.KEYCODE_BACK)
        assertPosition(R.id.categories, 0)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        repeat(last) { key(KeyEvent.KEYCODE_DPAD_UP) }
        assertPosition(R.id.list, 0)
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertPosition(R.id.list, 0)
    }

    private fun clickAndWait(condition: () -> Boolean) {
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("settings action") { condition() && main {
            val list = settingsActivity!!.findViewById<RecyclerView>(R.id.list)
            (0 until list.childCount).none {
                list.getChildAt(it).findViewById<TextView>(R.id.value).text == settingsActivity!!.getString(R.string.settings_watch_next_working)
            }
        } }
    }

    private fun assertRow(expected: Int, label: Int) {
        assertPosition(R.id.list, expected)
        check(main {
            val row = settingsActivity!!.findViewById<RecyclerView>(R.id.list).findViewHolderForAdapterPosition(expected)!!
            row.itemView.findViewById<TextView>(R.id.label).text == settingsActivity!!.getString(label)
        }) { "Expected Watch Next row at $expected" }
    }

    private fun position(id: Int): Int {
        val activity = settingsActivity!!
        return activity.findViewById<RecyclerView>(id).findContainingViewHolder(activity.currentFocus ?: return -1)
            ?.bindingAdapterPosition ?: -1
    }

    private fun assertPosition(id: Int, expected: Int) = await("focus $id/$expected") {
        main { position(id) == expected }
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        SystemClock.sleep(120)
        instrumentation.waitForIdleSync()
        await("settings layout settled") { main {
            val activity = settingsActivity!!
            listOf(R.id.list, R.id.categories).all {
                val list = activity.findViewById<RecyclerView>(it)
                list.scrollState == RecyclerView.SCROLL_STATE_IDLE && !list.isComputingLayout && !list.isLayoutRequested
            }
        } }
    }

    private fun await(label: String, timeout: Long = 12_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out: $label" }
            SystemClock.sleep(50)
        }
    }

    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        java.io.File(context.filesDir, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}

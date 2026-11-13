package com.hesier.lumy.ui.detail

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import com.hesier.lumy.LumyApp
import com.hesier.lumy.core.HdrType
import com.hesier.lumy.core.MediaDetail
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaPart
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.PlaybackSource
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import com.hesier.lumy.core.VideoStream
import com.hesier.lumy.data.SavedServer
import com.hesier.lumy.databinding.ActivityDetailBinding
import com.hesier.lumy.ui.player.PlayerActivity
import com.hesier.lumy.ui.widget.CardAdapter
import java.io.File
import java.lang.reflect.Proxy

/** Exercises real D-pad dispatch and the playback Activity result with local media. */
class DetailNavigationTest(private val instrumentation: Instrumentation) {
    private lateinit var detail: DetailActivity
    private lateinit var binding: ActivityDetailBinding
    private var playback: PlayerActivity? = null
    private val show = MediaItem("detail-show", MediaType.SHOW, "Detail show")
    private val seasons = (1..2).map { MediaItem("detail-season-$it", MediaType.SEASON, "Season $it", seasonNumber = it) }
    private var availableSeasons = seasons
    private val episodes = (1..13).map {
        val season = if (it < 13) 1 else 2
        MediaItem("detail-episode-$it", MediaType.EPISODE, "Episode $it", parentId = seasons[season - 1].id,
            grandparentId = show.id, seasonNumber = season, episodeNumber = it, durationMs = 600_000,
            summary = "Episode summary")
    }

    fun runAll(): String {
        instrumentation.waitForIdleSync()
        val app = LumyApp.instance
        val serverField = LumyApp::class.java.getDeclaredField("server").apply { isAccessible = true }
        val oldServer = serverField.get(app)
        val oldId = app.store.currentId
        val server = fixture()
        val entry = SavedServer("detail-navigation-fixture", server.session)
        val oldPlayback = Triple(app.settings.defaultQuality, app.settings.danmaku.enabled, app.settings.watchNextEnabled)
        val oldDetail = Triple(app.settings.detailShowCast, app.settings.detailShowSimilar, app.settings.autoPlayNext)
        try {
            app.settings.detailShowCast = true
            app.settings.detailShowSimilar = true
            app.settings.autoPlayNext = true
            app.store.add(entry)
            app.store.currentId = entry.id
            app.settings.defaultQuality = 0
            app.settings.danmaku.enabled = false
            app.settings.watchNextEnabled = false
            serverField.set(app, server)
            runScenario()
            checkSingleSeason()
        } finally {
            instrumentation.runOnMainSync {
                playback?.finish()
                if (::detail.isInitialized) detail.finish()
            }
            instrumentation.waitForIdleSync()
            app.settings.detailShowCast = oldDetail.first
            app.settings.detailShowSimilar = oldDetail.second
            app.settings.autoPlayNext = oldDetail.third
            kotlinx.coroutines.runBlocking { app.awaitPendingReports() }
            app.store.remove(entry.id)
            app.store.currentId = oldId
            app.settings.defaultQuality = oldPlayback.first
            app.settings.danmaku.enabled = oldPlayback.second
            app.settings.watchNextEnabled = oldPlayback.third
            serverField.set(app, oldServer)
        }
        return "Horizontal row boundaries, vertical navigation, single-season navigation, same-season autoplay return and cross-season autoplay return passed"
    }

    private fun checkSingleSeason() {
        instrumentation.runOnMainSync { detail.finish() }
        instrumentation.waitForIdleSync()
        availableSeasons = listOf(seasons[0].copy(title = "Named season", seasonNumber = 3))
        detail = instrumentation.startActivitySync(DetailActivity.intent(instrumentation.targetContext, episodes[0].id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
        binding = field(detail, "binding") as ActivityDetailBinding
        await("single season episodes loaded") { binding.episodes.adapter?.itemCount == 12 && binding.cast.childCount > 0 }
        mainCheck("single season title stays visible") {
            binding.seasons.visibility == View.VISIBLE &&
                (binding.seasons.findViewHolderForAdapterPosition(0)?.itemView as? android.widget.TextView)
                    ?.text?.toString() == availableSeasons.single().title
        }
        for (position in listOf(0, 5, 11)) {
            instrumentation.runOnMainSync { binding.episodes.scrollToPosition(position) }
            await("single season card attached") { binding.episodes.findViewHolderForAdapterPosition(position) != null }
            instrumentation.runOnMainSync { binding.episodes.findViewHolderForAdapterPosition(position)!!.itemView.requestFocus() }
            press(KeyEvent.KEYCODE_DPAD_UP)
            mainCheck("single season up reaches actions") { binding.actions.hasFocus() }
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            mainCheck("single season down restores selected episode") {
                binding.episodes.findViewHolderForAdapterPosition(position)?.itemView?.hasFocus() == true
            }
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            mainCheck("single season down reaches cast") { binding.cast.hasFocus() }
            press(KeyEvent.KEYCODE_DPAD_UP)
            mainCheck("single season cast up restores selected episode") {
                binding.episodes.findViewHolderForAdapterPosition(position)?.itemView?.hasFocus() == true
            }
        }
        instrumentation.runOnMainSync { binding.seasons.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus() }
        press(KeyEvent.KEYCODE_DPAD_UP)
        mainCheck("single season tab up reaches actions") { binding.actions.hasFocus() }
        instrumentation.runOnMainSync { binding.seasons.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus() }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        mainCheck("single season tab down reaches episodes") { binding.episodes.hasFocus() }
    }

    private fun runScenario() {
        detail = instrumentation.startActivitySync(DetailActivity.intent(instrumentation.targetContext, episodes[0].id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
        binding = field(detail, "binding") as ActivityDetailBinding
        await("episode rows loaded") { binding.episodes.adapter?.itemCount == 12 && binding.cast.childCount > 0 }
        checkRows()
        checkEpisodeVerticalNavigation()
        checkPlaybackReturn(1)
        checkPlaybackReturn(12)
    }

    private fun checkEpisodeVerticalNavigation() {
        instrumentation.runOnMainSync {
            DetailActivity::class.java.getDeclaredMethod("switchSeason", MediaItem::class.java)
                .apply { isAccessible = true }.invoke(detail, seasons[0])
        }
        await("long season loaded") { binding.episodes.adapter?.itemCount == 12 }
        instrumentation.runOnMainSync {
            binding.episodes.scrollToPosition(11)
        }
        await("last episode attached") { binding.episodes.findViewHolderForAdapterPosition(11) != null }
        instrumentation.runOnMainSync { binding.episodes.findViewHolderForAdapterPosition(11)!!.itemView.requestFocus() }
        await("last episode focused") { binding.episodes.findViewHolderForAdapterPosition(11)?.itemView?.hasFocus() == true }
        press(KeyEvent.KEYCODE_DPAD_UP)
        mainCheck("last episode up reaches seasons") { binding.seasons.hasFocus() }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        mainCheck("season down restores last episode") {
            binding.episodes.findViewHolderForAdapterPosition(11)?.itemView?.hasFocus() == true
        }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        mainCheck("last episode down reaches cast") { binding.cast.hasFocus() }
        press(KeyEvent.KEYCODE_DPAD_UP)
        mainCheck("cast up restores last episode") {
            binding.episodes.findViewHolderForAdapterPosition(11)?.itemView?.hasFocus() == true
        }
    }

    private fun checkRows() {
        instrumentation.runOnMainSync { binding.actions.getChildAt(0).requestFocus() }
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        mainCheck("first action stays on left") { binding.actions.getChildAt(0).hasFocus() }
        repeat(binding.actions.childCount - 1) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        val last = binding.actions.getChildAt(binding.actions.childCount - 1)
        mainCheck("right moves between actions") { last.hasFocus() }
        repeat(3) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        mainCheck("last action stays on right") { last.hasFocus() }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        mainCheck("down leaves action row") { !binding.actions.hasFocus() }
        press(KeyEvent.KEYCODE_DPAD_UP)
        mainCheck("up returns to action row") { binding.actions.hasFocus() }
        for (row in listOf(binding.seasons, binding.episodes, binding.cast, binding.similar, binding.fileInfo)) {
            if (row.visibility != View.VISIBLE || row.childCount == 0) continue
            instrumentation.runOnMainSync { row.getChildAt(0).requestFocus() }
            repeat(3) { press(KeyEvent.KEYCODE_DPAD_LEFT) }
            mainCheck("row ${row.id} stays on left") { row.hasFocus() }
            repeat((row.adapter?.itemCount ?: 0) + 3) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
            mainCheck("row ${row.id} stays on right") { row.hasFocus() }
        }
        instrumentation.runOnMainSync { detail.findViewById<View>(com.hesier.lumy.R.id.summary).requestFocus() }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        mainCheck("summary stays on right") { binding.summary.hasFocus() }
    }

    private fun checkPlaybackReturn(nextIndex: Int) {
        // Select the expected starting episode after the boundary checks have browsed the row.
        instrumentation.runOnMainSync {
            DetailActivity::class.java.getDeclaredMethod("selectEpisode", MediaItem::class.java)
                .apply { isAccessible = true }.invoke(detail, episodes[nextIndex - 1])
        }
        await("header ready") { binding.title.text.toString() == episodes[nextIndex - 1].title }
        instrumentation.runOnMainSync { binding.actions.getChildAt(0).requestFocus() }
        val monitor = instrumentation.addMonitor(PlayerActivity::class.java.name, null, false)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        playback = instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? PlayerActivity
        instrumentation.removeMonitor(monitor)
        val player = checkNotNull(playback) { "Player did not open" }
        await("next episode available") { field(field(player, "episodes"), "nextEpisode") != null }
        instrumentation.runOnMainSync {
            PlayerActivity::class.java.getDeclaredMethod("onEnded").apply { isAccessible = true }.invoke(player)
        }
        await("next episode loaded") { (field(player, "detail") as MediaDetail).item.id == episodes[nextIndex].id }
        press(KeyEvent.KEYCODE_BACK)
        await("detail selects current playback") {
            (field(detail, "selectedEpisode") as? MediaItem)?.id == episodes[nextIndex].id &&
                binding.title.text.toString() == episodes[nextIndex].title && binding.actions.hasFocus()
        }
        await("selected card and season updated") {
            field(detail, "currentSeasonId") == episodes[nextIndex].parentId &&
                (binding.episodes.adapter as? CardAdapter)?.selectedId == episodes[nextIndex].id &&
                (binding.episodes.adapter as? CardAdapter)?.indexOf(episodes[nextIndex].id) != -1
        }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        await("down focuses selected episode") {
            val adapter = binding.episodes.adapter as CardAdapter
            binding.episodes.findViewHolderForAdapterPosition(adapter.indexOf(episodes[nextIndex].id))?.itemView?.hasFocus() == true
        }
        playback = null
    }

    private fun fixture(): MediaServer {
        val video = File(instrumentation.targetContext.cacheDir, "detail-navigation.mp4")
        instrumentation.context.assets.open("watch-next/fixture.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
        val version = MediaVersion("detail-version", 0, "mp4", 32,
            VideoStream("h264", 128, 72, 10f, 8, "baseline", HdrType.NONE, null), emptyList(), emptyList(),
            listOf(MediaPart("detail-part", "/fixture.mp4", "mp4", null, 600_000)))
        val people = (1..8).map { MediaItem("person-$it", MediaType.PERSON, "Actor $it") }
        return Proxy.newProxyInstance(MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java)) { _, method, args ->
            when (method.name) {
                "getSession" -> ServerSession(ServerKind.PLEX, "detail-fixture", "Detail fixture", "http://127.0.0.1", "")
                "getSupportsFavorites", "getEmbedsSubtitles" -> false
                "isReachable" -> true
                "detail" -> if (args!![0] == show.id) MediaDetail(show, people = people, onDeck = episodes[0])
                    else MediaDetail(episodes.first { it.id == args[0] }, versions = listOf(version))
                "children" -> if (args!![0] == show.id) availableSeasons else episodes.filter { it.parentId == args[0] }
                "similar" -> listOf(show)
                "nextEpisode" -> episodes.getOrNull(episodes.indexOfFirst { it.id == (args!![0] as MediaItem).id } + 1)
                "playbackSource" -> PlaybackSource(listOf(video.toURI().toString()), false, emptyList(), 0, 0)
                "playbackHeaders" -> emptyMap<String, String>()
                "reportProgress", "stopPlayback", "saveStreamSelection" -> Unit
                "danmakuResource" -> null
                else -> error("Unexpected fixture call ${method.name}")
            }
        } as MediaServer
    }

    private fun field(target: Any?, name: String): Any? = checkNotNull(target).javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)

    private fun press(key: Int) {
        instrumentation.sendKeyDownUpSync(key)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
    }

    private fun mainCheck(message: String, condition: () -> Boolean) {
        instrumentation.runOnMainSync { check(condition()) { message } }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var passed = false
            instrumentation.runOnMainSync { passed = condition() }
            if (passed) return
            SystemClock.sleep(50)
        }
        error("Timed out: $message")
    }
}

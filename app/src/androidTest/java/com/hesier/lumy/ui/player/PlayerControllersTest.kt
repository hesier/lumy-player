@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.core.*
import com.hesier.lumy.data.SkipMode
import com.hesier.lumy.databinding.ActivityPlayerBinding
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

/** Exercises the extracted controllers through the real Activity and native D-pad events. */
internal class PlayerControllersTest(
    private val instrumentation: Instrumentation,
    private val activity: PlayerActivity,
    private val binding: ActivityPlayerBinding,
) {
    private val player get() = field("player") as ExoPlayer
    private val episodes get() = field("episodes") as PlayerEpisodeController
    private val overlays get() = field("overlays") as PlayerOverlayController
    private val settings get() = LumyApp.instance.settings

    fun runAll(): String {
        main { player.pause(); settings.autoPlayNext = false; settings.skipIntro = SkipMode.OFF }
        checkChapters()
        checkEpisodes()
        checkPlaylist()
        checkCreditsModes()
        checkMarkers()
        checkCancelledQueries()
        checkUnmarkedEnding()
        checkDismissedEnding()
        return "Chapters: D-pad entry, boundaries, button focus restoration and seek passed\n" +
            "Episodes: selection, in-player switch, next episode and playlist order passed\n" +
            "Markers: credits modes, unmarked final 10 seconds, natural completion, countdown pause and dismissal through completion passed\n" +
            "Late row and next-episode results cannot replace the current media state"
    }

    private fun checkChapters() {
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        val button = main { binding.buttons.focusedChild }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("chapter row focus") { binding.episodes.hasFocus() && binding.rowHint.visibility == View.GONE }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        main { check(binding.episodes.hasFocus()) }
        key(KeyEvent.KEYCODE_DPAD_UP)
        main { check(button?.hasFocus() == true && binding.episodes.visibility == View.GONE) }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("chapter row reopened") { binding.episodes.hasFocus() && selectedRowPosition() == 0 }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("second chapter focused") { selectedRowPosition() == 1 }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("chapter seek") { player.currentPosition >= 5_000 && player.playbackState == Player.STATE_READY }
        key(KeyEvent.KEYCODE_BACK)
        main { check(binding.episodes.visibility == View.GONE) }
    }

    private fun checkEpisodes() {
        switchTo(first)
        await("next episode loaded") { episodes.nextEpisode?.id == second.id }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("episode row focus") { binding.episodes.hasFocus() && selectedRowPosition() == 0 }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("second episode focused") { selectedRowPosition() == 1 }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("episode click switches inside Activity") {
            currentItem().id == second.id && binding.title.text.toString() == second.showTitle && player.playbackState == Player.STATE_READY
        }
        main { player.pause(); check(episodes.nextEpisode == null) }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("current episode selected after switch") { binding.episodes.hasFocus() && selectedRowPosition() == 1 }
        key(KeyEvent.KEYCODE_DPAD_UP)
        main { check(binding.buttons.getChildAt(0).hasFocus()) }
        key(KeyEvent.KEYCODE_BACK)
    }

    private fun checkPlaylist() {
        main { activity.intent.putExtra("playlist", "controller-playlist") }
        switchTo(first)
        await("playlist row loaded") { binding.rowHint.text == activity.getString(R.string.player_playlist_hint) }
        main { check(episodes.nextEpisode == null) { "Playlist end must override the series next episode" } }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("playlist selects current item") { binding.episodes.hasFocus() && selectedRowPosition() == 1 }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        await("previous playlist item focused") { selectedRowPosition() == 0 }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("playlist switch uses playlist order") {
            currentItem().id == second.id && episodes.nextEpisode?.id == first.id && player.playbackState == Player.STATE_READY
        }
        main { player.pause(); activity.intent.removeExtra("playlist") }
    }

    private fun checkMarkers() {
        main { settings.skipIntro = SkipMode.BUTTON }
        switchTo(first)
        await("manual intro prompt focused") { binding.skip.visibility == View.VISIBLE && binding.skip.hasFocus() }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("manual intro skip") { player.currentPosition >= 5_000 && binding.skip.visibility == View.GONE }
        main { settings.skipIntro = SkipMode.AUTO }
        switchTo(first)
        await("automatic intro skip") { player.currentPosition >= 5_000 }
        main { player.seekTo(0) }
        await("seek back into intro") { player.currentPosition < 1_000 && player.playbackState == Player.STATE_READY }
        Thread.sleep(700)
        main { check(player.currentPosition < 1_000) { "An already skipped marker ran twice" } }
        main { settings.autoPlayNext = true; settings.skipCredits = SkipMode.AUTO }
        switchTo(first, 16_000)
        await("up-next card") { overlays.upNextVisible && binding.upNextPlay.hasFocus() }
        Thread.sleep(1_300)
        val pausedLabel = main { binding.upNextPlay.text.toString() }
        Thread.sleep(1_300)
        main { check(pausedLabel == binding.upNextPlay.text.toString()) { "Paused countdown advanced" } }
        key(KeyEvent.KEYCODE_BACK)
        main { check(overlays.upNextDismissed && !overlays.upNextVisible) }
        switchTo(second)
        main { check(!overlays.upNextDismissed && !overlays.upNextVisible) }
        Thread.sleep(700)
        main { check(binding.skip.visibility == View.GONE && !overlays.upNextVisible) }
        switchTo(first, 5_000)
        main {
            overlays.reset()
            overlays.bind(field("server") as MediaServer, (field("detail") as MediaDetail).copy(
                markers = listOf(Marker(MarkerType.CREDITS, 0, 20_000))))
        }
        await("automatic credits countdown") { overlays.upNextVisible }
        main { player.play() }
        await("automatic credits countdown advances before completion") { currentItem().id == second.id }
        main { player.pause() }
    }

    private fun checkCreditsModes() {
        main { settings.autoPlayNext = true; settings.skipIntro = SkipMode.OFF }
        for (mode in listOf(SkipMode.OFF, SkipMode.BUTTON)) {
            main { settings.skipCredits = mode }
            switchTo(first, 16_000)
            Thread.sleep(700)
            main {
                check(!overlays.upNextVisible) { "$mode credits must not start an up-next countdown" }
                check((binding.skip.visibility == View.VISIBLE) == (mode == SkipMode.BUTTON))
            }
        }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("manual credits skip advances to next episode") { currentItem().id == second.id }
        main { settings.skipCredits = SkipMode.OFF }
        switchTo(first, 18_500)
        main { player.play() }
        await("credits off preserves natural completion and auto-play") { currentItem().id == second.id }
        main { player.pause() }
    }

    private fun checkUnmarkedEnding() {
        switchTo(first, 18_500)
        main {
            overlays.reset()
            overlays.bind(field("server") as MediaServer, (field("detail") as MediaDetail).copy(markers = emptyList()))
            player.play()
        }
        await("unmarked ending auto-plays after natural completion") { currentItem().id == second.id }
        // Use a stable clock to check both sides of the fallback boundary.
        var position = 49_999L
        var playedNext = false
        lateinit var probe: PlayerOverlayController
        main {
            player.pause()
            overlays.reset()
            settings.skipCredits = SkipMode.AUTO
            probe = PlayerOverlayController(activity, binding, player, settings,
                positionMs = { position }, durationMs = { 60_000L }, nextEpisode = { first },
                onSeek = { error("Unmarked ending must not seek") },
                onEnded = { error("Unmarked ending must not end early") },
                onPlayNext = { playedNext = true })
            probe.bind(field("server") as MediaServer, (field("detail") as MediaDetail).copy(markers = emptyList()))
        }
        try {
            Thread.sleep(700)
            main { check(!probe.upNextVisible) { "Up-next appeared before the last 10 seconds" }; position = 50_000 }
            await("unmarked final 10 seconds prompt") { probe.upNextVisible && binding.upNextPlay.hasFocus() }
            main {
                check(binding.upNextPlay.text == activity.getString(R.string.play_now)) { "Unmarked ending must not count down" }
                check(!playedNext)
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(playedNext) { "Manual play-next must remain available" }; position = 49_999 }
            await("seeking out of unmarked ending hides card") { !probe.upNextVisible }
        } finally {
            main { probe.reset() }
        }
    }

    private fun checkDismissedEnding() {
        main { settings.skipCredits = SkipMode.AUTO }
        switchTo(first, 16_000)
        await("up-next before cancellation") { overlays.upNextVisible }
        key(KeyEvent.KEYCODE_BACK)
        Thread.sleep(1_200)
        main { check(!activity.isFinishing && currentItem().id == first.id && player.currentPosition < 17_000) }
        main { player.play() }
        await("cancelled auto-play exits after natural completion") { activity.isDestroyed }
    }

    private fun switchTo(item: MediaItem, start: Long = 0) {
        main {
            activity.javaClass.getDeclaredMethod("switchTo", MediaItem::class.java, Long::class.javaPrimitiveType).apply {
                isAccessible = true
            }.invoke(activity, item, start)
            // Keep short marker ranges stable while the emulator initializes its decoder.
            player.pause()
        }
        await("fixture switch ready") {
            currentItem().id == item.id && player.playbackState == Player.STATE_READY && binding.title.text.toString() == item.showTitle
        }
        main { player.pause() }
    }

    private fun checkCancelledQueries() {
        val pending = mutableListOf<Continuation<Any?>>()
        main {
            val server = field("server") as MediaServer
            val stalled = Proxy.newProxyInstance(MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java)) { _, method, args ->
                when (method.name) {
                    "children", "nextEpisode" -> {
                        @Suppress("UNCHECKED_CAST")
                        pending.add(args!!.last() as Continuation<Any?>)
                        COROUTINE_SUSPENDED
                    }
                    else -> method.invoke(server, *(args ?: emptyArray()))
                }
            } as MediaServer
            val base = field("detail") as MediaDetail
            episodes.bind(stalled, detail(base, first.id), null)
            check(pending.size == 2)
            episodes.bind(server, detail(base, second.id), null)
            pending[0].resume(listOf(first))
            pending[1].resume(first)
        }
        instrumentation.waitForIdleSync()
        main {
            check(episodes.nextEpisode == null)
            val adapter = checkNotNull(binding.episodes.adapter) as com.hesier.lumy.ui.widget.CardAdapter
            check(adapter.itemCount == 2 && adapter.selectedId == second.id)
        }
    }

    private fun currentItem() = (field("detail") as MediaDetail).item
    private fun selectedRowPosition(): Int {
        val focused = binding.episodes.focusedChild ?: return -1
        return binding.episodes.getChildAdapterPosition(focused)
    }
    private fun field(name: String): Any? = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
        Thread.sleep(60)
    }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (main { player.playerError?.let { throw it }; condition() }) return
            Thread.sleep(25)
        }
        val state = main { "position=${player.currentPosition} state=${player.playbackState} controls=${binding.controls.visibility}" +
            " skip=${binding.skip.visibility} focus=${binding.skip.hasFocus()}" }
        error("Timed out: $label ($state)")
    }
    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    companion object {
        val first = MediaItem("controller-episode-1", MediaType.EPISODE, "First", showTitle = "Controller series",
            parentId = "controller-season", seasonNumber = 1, episodeNumber = 1, durationMs = 20_000)
        val second = first.copy(id = "controller-episode-2", title = "Second", episodeNumber = 2)
        val season = listOf(first, second)
        val playlist = listOf(second, first)

        fun detail(base: MediaDetail, id: String): MediaDetail {
            if (id == base.item.id) return base
            val item = season.first { it.id == id }
            return base.copy(item = item, markers = if (item == first) listOf(
                Marker(MarkerType.INTRO, 0, 5_000), Marker(MarkerType.CREDITS, 15_000, 20_000),
            ) else emptyList())
        }
    }
}

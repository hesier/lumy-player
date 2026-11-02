package com.hesier.lumy.ui.player

import android.app.Instrumentation
import com.hesier.lumy.R
import com.hesier.lumy.data.DanmakuLoad

/** Runs real PlayerDanmaku menus and storage with an isolated source configuration. */
class DanmakuPlayerFlowTest(private val instrumentation: Instrumentation) {
    fun runAll(live: Boolean = false): String = if (live) runOne(true, false) else
        listOf(runOne(false, false), runOne(false, true)).joinToString("\n")

    private fun runOne(live: Boolean, matchAvailable: Boolean): String {
        val server = if (live) null else DanmakuPlayerFlowServer().apply {
            if (matchAvailable) matchBody = """{"success":true,"isMatched":true,"matches":[{"animeId":"A","episodeId":"A2","shift":1.5}]}"""
        }
        val fixture = DanmakuPlayerFlowFixture(instrumentation, live)
        try {
            fixture.open(server?.endpoint)
            val initial = fixture.awaitLoaded(automatic = true)
            if (!live) check(initial.match?.anime?.id == "A" && initial.match?.episode?.id == "A2")
            if (!live) check(initial.sourceOffsetMs == if (matchAvailable) 1_500L else 0L)
            if (!live) check(initial.match?.episode?.number == "2")
            else check(!initial.match?.episode?.id.isNullOrBlank())
            fixture.expectToast(fixture.feedbackPrefix(R.string.danmaku_auto_matched))
            fixture.checkVisible()
            checkAutomaticQuery(server)
            val manual = manualSelection(fixture)
            forceReload(fixture, server)
            if (server != null) checkFailedReload(fixture, server)
            restoreAutomatic(fixture, manual)
            return "Player danmaku flow: ${if (live) "live" else "loopback"} automatic, D-pad manual selection, saved match, reload, restore and feedback passed"
        } finally {
            try { fixture.close() } finally { server?.close() }
        }
    }

    private fun checkAutomaticQuery(server: DanmakuPlayerFlowServer?) {
        if (server == null) return
        if (server.matchBody != null) {
            check(server.paths.any { "/match" in it } && server.paths.none { "/search/anime" in it })
            return
        }
        val searches = server.paths.filter { "/search/anime" in it }
        check(searches.size == 2)
        check(searches[0].contains("Flow%20Show%20S01E02") && searches[1].contains("Flow%20Show"))
    }

    private fun manualSelection(fixture: DanmakuPlayerFlowFixture): DanmakuLoad.Loaded {
        fixture.toasts.clear()
        fixture.openMenu()
        fixture.pick(fixture.text(R.string.danmaku_manual_match))
        fixture.submitSearch()
        val workTitle = if (fixture.live) fixture.pickFirstAnime() else {
            fixture.pick("Flow B")
            "Flow B"
        }
        fixture.pickEpisode(workTitle, if (fixture.live) 1 else 2)
        val loaded = fixture.awaitLoaded(automatic = false)
        val match = checkNotNull(loaded.match)
        if (!fixture.live) check(match.anime.id == "B" && match.episode.id == "B3") {
            "Manual selection expected B/B3, actual ${match.anime.id}/${match.episode.id}"
        }
        check(match.episode.number == if (fixture.live) "2" else "3")
        check(fixture.store.match(fixture.mediaKey(), loaded.source.identity)?.episode?.id == match.episode.id)
        fixture.expectToast(fixture.text(R.string.danmaku_loading))
        fixture.expectToast(fixture.feedbackPrefix(R.string.danmaku_match_loaded))
        fixture.checkVisible()
        return loaded
    }

    private fun forceReload(fixture: DanmakuPlayerFlowFixture, server: DanmakuPlayerFlowServer?) {
        val selected = fixture.loaded()
        val before = server?.commentRequests()
        fixture.toasts.clear()
        fixture.openMenu()
        fixture.pick(fixture.text(R.string.danmaku_reload))
        fixture.await("new reload result") { fixture.result() is DanmakuLoad.Loaded && fixture.result() !== selected }
        val reloaded = fixture.awaitLoaded(automatic = false)
        check(selected.match?.episode?.id == reloaded.match?.episode?.id)
        if (server != null) check(server.commentRequests() == checkNotNull(before) + 1)
        fixture.expectToast(fixture.text(R.string.danmaku_loading))
        fixture.expectToast(fixture.main { fixture.activity.getString(R.string.danmaku_loaded, reloaded.comments.size) })
        fixture.checkVisible()
    }

    private fun checkFailedReload(fixture: DanmakuPlayerFlowFixture, server: DanmakuPlayerFlowServer) {
        server.failComments = true
        fixture.toasts.clear()
        fixture.openMenu()
        fixture.pick(fixture.text(R.string.danmaku_reload))
        fixture.await("failed reload state") { fixture.result() is DanmakuLoad.Failed }
        check((fixture.result() as DanmakuLoad.Failed).manual)
        fixture.expectToast(fixture.text(R.string.danmaku_error_unavailable))
        server.failComments = false
    }

    private fun restoreAutomatic(fixture: DanmakuPlayerFlowFixture, manual: DanmakuLoad.Loaded) {
        fixture.toasts.clear()
        fixture.openMenu()
        fixture.pick(fixture.text(R.string.danmaku_restore_auto))
        val restored = fixture.awaitLoaded(automatic = true)
        if (!fixture.live) check(restored.match?.anime?.id == "A" && restored.match?.episode?.id == "A2")
        if (!fixture.live) check(restored.match?.episode?.number == "2")
        else check(!restored.match?.episode?.id.isNullOrBlank())
        check(fixture.store.match(fixture.mediaKey(), manual.source.identity) == null)
        fixture.expectToast(fixture.text(R.string.danmaku_loading))
        fixture.expectToast(fixture.feedbackPrefix(R.string.danmaku_auto_matched))
        fixture.checkVisible()
    }
}

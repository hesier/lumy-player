package com.hesier.lumy.ui.player

import android.app.Instrumentation
import com.hesier.lumy.R
import com.hesier.lumy.data.DanmakuLoad
import com.hesier.lumy.data.DanmakuSourceKind

/** Runs real PlayerDanmaku menus and storage with an isolated source configuration. */
class DanmakuPlayerFlowTest(private val instrumentation: Instrumentation) {
    fun runAll(live: Boolean = false): String = if (live) runOne(true, false) else
        (0..6).joinToString("\n") { runCase(it) }

    fun runCase(index: Int): String = when (index) {
        0 -> runOne(false, false)
        1 -> runOne(false, true)
        2 -> runWithPlugin()
        3 -> runConfirmation(1_250)
        4 -> runConfirmation(-750)
        5 -> runCancelledConfirmation()
        6 -> runMediaSwitches()
        else -> error("Unknown flow case")
    }

    private fun runMediaSwitches(): String {
        val server = DanmakuPlayerFlowServer()
        val fixture = DanmakuPlayerFlowFixture(instrumentation, live = false)
        val master = com.hesier.lumy.data.DanmakuSettings(fixture.preferences)
        try {
            fixture.open(server.endpoint, withPlugin = true)
            fixture.awaitLoaded(automatic = false); fixture.checkVisible()
            val firstKey = fixture.mediaKey()
            fixture.openMenu(); fixture.switchEpisode(3)
            fixture.pick(fixture.text(R.string.danmaku_media_enabled))
            fixture.awaitLoaded(automatic = false)
            check(fixture.store.mediaEnabled(firstKey) && fixture.store.mediaEnabled(fixture.mediaKey()))
            fixture.switchEpisode(2); fixture.awaitLoaded(automatic = false)
            fixture.openMenu(); fixture.pick(fixture.text(R.string.danmaku_media_enabled))
            fixture.checkStopped()
            check(master.enabled && !fixture.store.mediaEnabled(firstKey))
            val requests = server.paths.size
            fixture.reopenController(); fixture.checkStopped()
            check(requests == server.paths.size)
            fixture.store.clearCache(); fixture.store.clearMatches()
            check(!fixture.store.mediaEnabled(firstKey))
            fixture.switchEpisode(3)
            check(firstKey != fixture.mediaKey())
            fixture.awaitLoaded(automatic = false); fixture.checkVisible()
            check(fixture.store.mediaEnabled(fixture.mediaKey()))
            fixture.main { master.enabled = false }
            fixture.checkStopped()
            val stoppedRequests = server.paths.size
            fixture.switchEpisode(4); fixture.checkStopped()
            check(stoppedRequests == server.paths.size)
            fixture.switchEpisode(2); fixture.checkStopped()
            fixture.main { master.enabled = true }
            fixture.checkStopped()
            check(!fixture.store.mediaEnabled(firstKey))
            fixture.openMenu(); fixture.pick(fixture.text(R.string.danmaku_media_enabled))
            fixture.awaitLoaded(automatic = false); fixture.checkVisible()
            check(master.enabled && fixture.store.mediaEnabled(firstKey))
            return "Master gate, per-media switch persistence, episode isolation, no requests while disabled and cache/match independence passed"
        } finally {
            try { fixture.close() } finally { server.close() }
        }
    }

    private fun ambiguousServer(offsetMs: Long = 1_250) = DanmakuPlayerFlowServer().apply {
        ambiguous = true
        matchBody = """{"success":true,"isMatched":true,"matches":[{"animeId":"A","episodeId":"A2","shift":${offsetMs / 1000.0}},{"animeId":"B","episodeId":"B2"}]}"""
    }

    private fun runConfirmation(offsetMs: Long): String {
        val server = ambiguousServer(offsetMs)
        val fixture = DanmakuPlayerFlowFixture(instrumentation, live = false)
        try {
            fixture.open(server.endpoint)
            fixture.await("ambiguous result") { fixture.result() is DanmakuLoad.NeedsMatch }
            check(server.commentRequests() == 0)
            check(fixture.actionHasFocus()) { "Ambiguity opened a panel automatically" }
            val pending = fixture.result() as DanmakuLoad.NeedsMatch
            val suggestion = pending.suggestions.first { it.anime.id == "A" }
            check(suggestion.sourceOffsetMs == offsetMs)
            fixture.openMenu()
            fixture.pick(fixture.text(R.string.danmaku_manual_match))
            val timing = if (offsetMs > 0) "1.25" else "-0.75"
            fixture.await("candidate source timing visible") { fixture.screenTexts().any { it.contains(timing) } }
            if (offsetMs > 0) captureDanmakuTest(instrumentation, "danmaku-candidates-confirmation.png")
            fixture.pick("Flow Show")
            fixture.pickEpisode("Flow Show", 0)
            val confirmed = fixture.awaitLoaded(automatic = false)
            check(confirmed.match?.episode?.id == "A2" && confirmed.sourceOffsetMs == offsetMs)
            fixture.checkVisible()
            fixture.reopenController()
            check(fixture.awaitLoaded(automatic = false).sourceOffsetMs == offsetMs)
            fixture.checkVisible()
            return "Player confirmation: candidate shift $offsetMs preserved after explicit selection and store/controller reopen passed"
        } finally {
            try { fixture.close() } finally { server.close() }
        }
    }

    private fun runCancelledConfirmation(): String {
        val server = ambiguousServer()
        val fixture = DanmakuPlayerFlowFixture(instrumentation, live = false)
        try {
            fixture.open(server.endpoint)
            fixture.await("ambiguous result") { fixture.result() is DanmakuLoad.NeedsMatch }
            val source = (fixture.result() as DanmakuLoad.NeedsMatch).source
            fixture.openMenu()
            fixture.pick(fixture.text(R.string.danmaku_manual_match))
            fixture.pick("Flow Show")
            fixture.await("episode panel") { fixture.screenTexts().contains("Flow A episode 2") }
            fixture.key(android.view.KeyEvent.KEYCODE_BACK)
            fixture.await("focus restored") { fixture.actionHasFocus() }
            check(fixture.store.match(fixture.mediaKey(), source.identity) == null)
            check(server.commentRequests() == 0)
            fixture.openMenu()
            fixture.pick(fixture.text(R.string.danmaku_manual_match))
            fixture.pick("Flow Show")
            fixture.switchEpisode(3)
            fixture.await("new episode result") { fixture.result() is DanmakuLoad.NeedsMatch }
            check(!fixture.screenTexts().contains("Flow A episode 2"))
            check(fixture.store.match(fixture.mediaKey(), source.identity) == null)
            check(server.commentRequests() == 0)
            fixture.openMenu()
            fixture.pick(fixture.text(R.string.danmaku_manual_match))
            fixture.pick("Flow Show")
            fixture.pickEpisode("Flow Show", 1)
            check(fixture.awaitLoaded(automatic = false).sourceOffsetMs == 0L)
            return "Player confirmation: back restores focus without writes, switching invalidates old panel, another episode resets shift passed"
        } finally {
            try { fixture.close() } finally { server.close() }
        }
    }

    private fun runWithPlugin(): String {
        val server = DanmakuPlayerFlowServer()
        val fixture = DanmakuPlayerFlowFixture(instrumentation, live = false)
        try {
            fixture.open(server.endpoint, withPlugin = true)
            check(fixture.awaitLoaded(automatic = false).source.kind == DanmakuSourceKind.SERVER)
            check(server.paths.size == 1 && server.paths.single() == "/plugin")
            fixture.checkVisible()
            val manual = manualSelection(fixture)
            val pluginReads = server.paths.count { it == "/plugin" }
            fixture.reopenController()
            val remembered = fixture.awaitLoaded(automatic = false)
            check(remembered.source == manual.source && remembered.match?.episode?.id == manual.match?.episode?.id)
            check(server.paths.count { it == "/plugin" } == pluginReads)
            fixture.checkVisible()
            forceReload(fixture, server)
            checkFailedReload(fixture, server)
            check(fixture.store.match(fixture.mediaKey(), manual.source.identity) != null)
            restoreAutomatic(fixture, manual, withPlugin = true)
            check(server.paths.count { it == "/plugin" } > pluginReads)
            return "Player plugin flow: manual override, remembered selection after reopen, reload/failure retention and restore to server passed"
        } finally {
            try { fixture.close() } finally { server.close() }
        }
    }

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
        val previous = fixture.result()
        fixture.pickEpisode(workTitle, if (fixture.live) 1 else 2)
        fixture.await("manual match installed") {
            val value = fixture.result() as? DanmakuLoad.Loaded
            value != null && value !== previous && value.match != null && !value.automatic
        }
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

    private fun restoreAutomatic(fixture: DanmakuPlayerFlowFixture, manual: DanmakuLoad.Loaded, withPlugin: Boolean = false) {
        fixture.toasts.clear()
        val previous = fixture.result()
        fixture.openMenu()
        fixture.pick(fixture.text(R.string.danmaku_restore_auto))
        fixture.await("restored source installed") { fixture.result() is DanmakuLoad.Loaded && fixture.result() !== previous }
        val restored = fixture.awaitLoaded(automatic = !withPlugin)
        if (withPlugin) check(restored.source.kind == DanmakuSourceKind.SERVER && restored.match == null)
        else if (!fixture.live) check(restored.match?.anime?.id == "A" && restored.match?.episode?.number == "2")
        else check(!restored.match?.episode?.id.isNullOrBlank())
        check(fixture.store.match(fixture.mediaKey(), manual.source.identity) == null)
        fixture.expectToast(fixture.text(R.string.danmaku_loading))
        fixture.expectToast(if (withPlugin) fixture.text(R.string.danmaku_source_server)
            else fixture.feedbackPrefix(R.string.danmaku_auto_matched))
        fixture.checkVisible()
    }
}

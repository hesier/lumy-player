package com.hesier.lumy.ui.player

import com.hesier.lumy.R
import com.hesier.lumy.data.*
import com.hesier.lumy.net.danmaku.DanmakuAnime
import com.hesier.lumy.net.danmaku.DanmakuEpisode
import com.hesier.lumy.ui.ThemedActivity
import com.hesier.lumy.ui.widget.InputPanel
import com.hesier.lumy.ui.widget.Option
import com.hesier.lumy.ui.widget.OptionsPanel

/** Explicit search never opens automatically during video startup. */
internal class DanmakuActions(
    private val activity: ThemedActivity,
    private val repository: DanmakuRepository,
    private val current: () -> Pair<DanmakuMedia, DanmakuChoice>?,
    private val revision: () -> Int,
    private val choose: (DanmakuChoice) -> Unit,
    private val selected: (DanmakuAnime, DanmakuEpisode, DanmakuChoice) -> Unit,
    private val run: (suspend () -> Unit) -> Unit,
) {
    fun search() {
        val target = current() ?: return
        val serial = revision()
        InputPanel(activity, text(R.string.danmaku_search), target.first.title,
            text(R.string.danmaku_search_hint), text(R.string.danmaku_search)) { keyword ->
            if (!sameMedia(target.first, serial)) return@InputPanel
            run {
                val source = if (target.second.kind == DanmakuSourceKind.SERVER) online() else target.second
                val matches = repository.search(source, keyword.trim())
                if (sameMedia(target.first, serial)) showAnimes(target.first, source, matches, serial)
            }
        }.show()
    }

    private fun showAnimes(media: DanmakuMedia, source: DanmakuChoice, animes: List<DanmakuAnime>, serial: Int) {
        if (animes.isEmpty()) {
            OptionsPanel(activity, text(R.string.danmaku_no_results), listOf(Option(text(R.string.danmaku_search)))) { search() }.show()
            return
        }
        OptionsPanel(activity, text(R.string.danmaku_search), animes.map { Option(it.title, it.type) }) { index ->
            if (sameMedia(media, serial)) run {
                val details = repository.details(source, animes[index])
                if (sameMedia(media, serial)) showEpisodes(media, source, details, serial)
            }
        }.show()
    }

    private fun showEpisodes(media: DanmakuMedia, source: DanmakuChoice, anime: DanmakuAnime, serial: Int) {
        if (anime.episodes.isEmpty()) {
            OptionsPanel(activity, text(R.string.danmaku_no_results), listOf(Option(text(R.string.danmaku_search)))) { search() }.show()
            return
        }
        OptionsPanel(activity, anime.title, anime.episodes.map { Option(it.title, it.number) }) { index ->
            if (sameMedia(media, serial)) selected(anime, anime.episodes[index], source)
        }.show()
    }

    fun sources() {
        val target = current() ?: return
        val serial = revision()
        val services = DanmakuServices(activity)
        val choices = buildList {
            if (target.first.plugin != null) add(DanmakuChoice(DanmakuSourceKind.SERVER, "server:${target.first.serverKey}"))
            add(DanmakuChoice(DanmakuSourceKind.OFFICIAL, "official:dandanplay"))
            runCatching { services.custom() }.getOrNull()?.let { add(DanmakuChoice(DanmakuSourceKind.CUSTOM, it.identity)) }
        }
        val labels = choices.map { choice ->
            val label = when (choice.kind) {
                DanmakuSourceKind.SERVER -> R.string.danmaku_source_server
                DanmakuSourceKind.OFFICIAL -> R.string.danmaku_source_official
                DanmakuSourceKind.CUSTOM -> R.string.danmaku_source_custom
            }
            Option(text(label), selected = choice == target.second)
        }
        OptionsPanel(activity, text(R.string.danmaku_switch_source), labels) {
            if (sameMedia(target.first, serial)) choose(choices[it])
        }.show()
    }

    private fun online(): DanmakuChoice {
        val endpoint = DanmakuServices(activity).endpoint
        return DanmakuChoice(if (endpoint.official) DanmakuSourceKind.OFFICIAL else DanmakuSourceKind.CUSTOM, endpoint.identity)
    }
    private fun sameMedia(media: DanmakuMedia, serial: Int) = current()?.first?.key == media.key && revision() == serial
    private fun text(id: Int) = activity.getString(id)
}

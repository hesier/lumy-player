package com.hesier.lumy.ui.player

import android.app.Dialog
import android.view.View
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
    private val selected: (DanmakuAnime, DanmakuEpisode, DanmakuChoice, Long) -> Unit,
    private val run: (suspend () -> Unit) -> Unit,
    private val returnToMenu: () -> Unit,
) {
    private data class Target(val media: DanmakuMedia, val source: DanmakuChoice, val serial: Int, val session: Int)
    private var panel: Dialog? = null
    private var session = 0
    private var returnFocus: View? = null

    fun open(pending: DanmakuLoad.NeedsMatch?) {
        val value = current() ?: return
        returnFocus = activity.currentFocus
        session++
        val target = Target(value.first, value.second, revision(), session)
        val suggestions = pending?.takeIf { it.source == target.source }?.suggestions.orEmpty()
        if (suggestions.isEmpty()) search(target, onBack = returnToMenu)
        else showCandidates(target, suggestions)
    }

    private fun showCandidates(target: Target, suggestions: List<DanmakuSuggestion>, focused: Int = 0) {
        val options = suggestions.mapIndexed { index, suggestion ->
            val conflictingOffset = suggestions.any { it.anime.id == suggestion.anime.id &&
                it.episode?.id == suggestion.episode?.id && it.sourceOffsetMs != suggestion.sourceOffsetMs }
            val timing = if (suggestion.sourceOffsetMs != 0L || conflictingOffset) {
                val seconds = java.math.BigDecimal.valueOf(suggestion.sourceOffsetMs, 3).stripTrailingZeros().toPlainString()
                activity.getString(R.string.danmaku_match_source_offset, seconds)
            } else null
            val details = listOfNotNull(suggestion.anime.year?.toString(), suggestion.anime.type, timing,
                suggestion.episode?.let { activity.getString(R.string.danmaku_match_recommended, it.title) },
                text(reasonText(suggestion.reason))).filter { it.isNotBlank() }.joinToString(" · ")
            Option(suggestion.anime.title, details, selected = index == focused)
        } + Option(text(R.string.danmaku_match_search_again), selected = focused == suggestions.size)
        show(OptionsPanel(activity, text(R.string.danmaku_match_candidates), options) { index ->
            if (!valid(target)) return@OptionsPanel
            val back = { showCandidates(target, suggestions, index) }
            if (index == suggestions.size) search(target, onBack = back) else {
                val suggestion = suggestions[index]
                // Complete snapshots may use a canonical ID that is not a query alias.
                if (suggestion.anime.episodes.isNotEmpty()) {
                    showEpisodes(target, target.source, suggestion.anime, suggestion, back)
                } else run {
                    val details = repository.details(target.source, suggestion.anime)
                    if (valid(target)) showEpisodes(target, target.source, details, suggestion, back)
                }
            }
        }, target, returnToMenu)
    }

    fun invalidate() {
        session++
        val previous = panel
        panel = null
        if (previous?.window?.decorView?.isAttachedToWindow == true) previous.dismiss()
    }

    private fun search(target: Target, keyword: String = target.media.title, onBack: () -> Unit) {
        if (!valid(target)) return
        show(InputPanel(activity, text(R.string.danmaku_search), keyword,
            text(R.string.danmaku_search_hint), text(R.string.danmaku_search)) { query ->
            if (!valid(target)) return@InputPanel
            run {
                val source = if (target.source.kind == DanmakuSourceKind.SERVER) repository.onlineChoice() else target.source
                val matches = repository.search(source, query.trim())
                if (valid(target)) showAnimes(target, source, matches, onBack = { search(target, query, onBack) })
            }
        }, target, onBack)
    }

    private fun showAnimes(target: Target, source: DanmakuChoice, animes: List<DanmakuAnime>,
        focused: Int = 0, onBack: () -> Unit) {
        if (animes.isEmpty()) { noResults(target, onBack); return }
        show(OptionsPanel(activity, text(R.string.danmaku_search), animes.mapIndexed { index, anime ->
            Option(anime.title, anime.type, selected = index == focused)
        }) { index ->
            if (valid(target)) run {
                val details = repository.details(source, animes[index])
                if (valid(target)) showEpisodes(target, source, details, onBack = {
                    showAnimes(target, source, animes, index, onBack)
                })
            }
        }, target, onBack)
    }

    private fun showEpisodes(target: Target, source: DanmakuChoice, anime: DanmakuAnime,
        suggestion: DanmakuSuggestion? = null, onBack: () -> Unit) {
        if (anime.episodes.isEmpty()) { noResults(target, onBack) { search(target, onBack = onBack) }; return }
        show(OptionsPanel(activity, anime.title, anime.episodes.map {
            Option(it.title, it.number, selected = it.id == suggestion?.episode?.id)
        }) { index ->
            if (valid(target)) {
                val episode = anime.episodes[index]
                val offset = suggestion?.takeIf { it.episode?.id == episode.id }?.sourceOffsetMs ?: 0L
                selected(anime, episode, source, offset)
            }
        }, target, onBack)
    }

    private fun noResults(target: Target, onBack: () -> Unit, retry: () -> Unit = onBack) {
        show(OptionsPanel(activity, text(R.string.danmaku_no_results), listOf(Option(text(R.string.danmaku_search)))) {
            if (valid(target)) retry()
        }, target, onBack)
    }

    private fun show(dialog: Dialog, target: Target, onBack: () -> Unit) {
        panel = dialog
        dialog.setOnDismissListener { if (panel === dialog) panel = null }
        dialog.setOnCancelListener {
            panel = null
            if (valid(target)) onBack()
            else returnFocus?.takeIf { it.isAttachedToWindow }?.requestFocus()
        }
        dialog.show()
    }

    private fun valid(target: Target): Boolean {
        val value = current() ?: return false
        return value.first.key == target.media.key && value.second == target.source && revision() == target.serial && session == target.session
    }

    private fun text(id: Int) = activity.getString(id)
}

internal fun reasonText(reason: DanmakuMatchReason): Int = when (reason) {
    DanmakuMatchReason.AMBIGUOUS -> R.string.danmaku_match_ambiguous
    DanmakuMatchReason.METADATA -> R.string.danmaku_match_metadata
    DanmakuMatchReason.INCOMPLETE -> R.string.danmaku_match_incomplete
    DanmakuMatchReason.NO_MATCH -> R.string.danmaku_match_no_match
}

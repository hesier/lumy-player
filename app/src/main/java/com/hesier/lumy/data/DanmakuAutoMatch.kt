package com.hesier.lumy.data

import com.hesier.lumy.core.MediaType
import com.hesier.lumy.net.danmaku.*
import kotlinx.coroutines.withTimeoutOrNull

/** An identification deadline never cancels the player or the subsequent comment download. */
internal class DanmakuAutoMatch(private val api: DanmakuApi, private val timeoutMs: Long = IDENTIFICATION_TIMEOUT_MS) {
    private val details = mutableMapOf<String, DanmakuAnime?>()
    private val assessments = mutableListOf<DanmakuAssessment>()
    private val confirmed = mutableListOf<DanmakuSuggestion>()
    private var requests = 0
    private var incomplete = false
    private var unseenUpperBound = Double.NEGATIVE_INFINITY

    suspend fun run(media: DanmakuMedia): DanmakuDecision {
        return DanmakuDiagnostics.stage(DanmakuStage.IDENTIFY) { identifyWithDeadline(media) }
    }

    private suspend fun identifyWithDeadline(media: DanmakuMedia): DanmakuDecision {
        val result = withTimeoutOrNull(timeoutMs) { identify(media) }
        if (result != null) return result
        val complete = assessments.any { it.titleScore >= MIN_RELEVANCE && it.suggestion.episode != null }
        if (!complete && confirmed.isEmpty()) throw DanmakuException(DanmakuFailure.CONNECTION)
        incomplete = true
        return pending(DanmakuMatcher.decide(assessments, incomplete = true))
    }

    private suspend fun identify(media: DanmakuMedia): DanmakuDecision {
        val keywords = DanmakuMatcher.keywords(media)
        val response = if (keywords.isNotEmpty() && (media.type == MediaType.MOVIE || keywords.size > 1))
            bounded("match") { api.match(keywords.first()) } else null
        incomplete = incomplete || response?.truncated == true
        if (response != null) {
            val direct = inspectMatches(media, response)
            if (direct != null) return direct
        }
        val candidates = searches(media)
        val ranked = candidates.sortedWith(compareByDescending<DanmakuAnime> { DanmakuMatcher.titleScore(media, it) }.thenBy { it.id })
        for (candidate in ranked.take(MAX_SEARCH_CANDIDATES)) inspectSearch(media, candidate)
        for (candidate in ranked.drop(MAX_SEARCH_CANDIDATES)) markUninspected(media, candidate)
        val best = assessments.filter { it.eligible }.maxOfOrNull { it.score } ?: AUTO_SCORE
        incomplete = incomplete || unseenUpperBound > best - MIN_GAP + EPSILON
        val decision = DanmakuMatcher.decide(assessments, incomplete)
        return if (confirmed.size > 1 || incomplete) pending(decision) else decision
    }

    private suspend fun inspectMatches(media: DanmakuMedia, response: DanmakuMatchResponse): DanmakuDecision? {
        val ids = response.candidates.map { it.animeId }.distinct().take(MAX_MATCH_DETAILS)
        for (candidate in response.candidates) {
            if (candidate.animeId !in ids) { incomplete = true; continue }
            val anime = detail(candidate.animeId) ?: continue
            val episodes = anime.episodes.filter { it.id == candidate.episodeId }.distinct()
            val episode = episodes.singleOrNull() ?: continue
            val suggestion = DanmakuSuggestion(anime, episode, candidate.shiftMs)
            if (response.isMatched == true && DanmakuMatcher.confirmed(media, suggestion)) confirmed += suggestion
            assessments += DanmakuMatcher.assess(media, suggestion)
        }
        val unique = confirmed.distinctBy { Triple(it.anime.id, it.episode?.id, it.sourceOffsetMs) }
        confirmed.clear(); confirmed += unique
        if (response.isMatched == true && !incomplete && confirmed.size == 1)
            return DanmakuDecision(selected = confirmed.single())
        return null
    }

    private suspend fun searches(media: DanmakuMedia): List<DanmakuAnime> {
        val titles = listOfNotNull(media.title, media.originalTitle).filter { it.isNotBlank() }.distinctBy { normalizeKeyword(it) }
        val keywords = titles.flatMap { DanmakuMatcher.keywords(media, it) }.distinctBy { normalizeKeyword(it) }
        val candidates = linkedMapOf<String, DanmakuAnime>()
        for (keyword in keywords.take(MAX_SEARCH_REQUESTS)) {
            for (candidate in bounded("search") { api.search(keyword) }.orEmpty()) {
                candidates[candidate.id] = candidate
                if (candidates.size > MAX_SEARCH_CANDIDATES) {
                    val removed = candidates.values.minWithOrNull(compareBy<DanmakuAnime> {
                        DanmakuMatcher.titleScore(media, it)
                    }.thenByDescending { it.id })!!
                    markUninspected(media, removed)
                    candidates.remove(removed.id)
                }
            }
        }
        if (keywords.size > MAX_SEARCH_REQUESTS) incomplete = true
        return candidates.values.toList()
    }

    private suspend fun inspectSearch(media: DanmakuMedia, candidate: DanmakuAnime) {
        if (DanmakuMatcher.titleScore(media, candidate) < MIN_RELEVANCE) return
        if (requests >= MAX_DETAILS && candidate.id !in details) { markUninspected(media, candidate); return }
        val anime = detail(candidate.id) ?: return
        val assessment = DanmakuMatcher.assess(media, DanmakuSuggestion(anime))
        // Search does not invent a zero-offset alternative to an existing service mapping.
        if (assessments.none { it.suggestion.anime.id == anime.id && it.suggestion.episode?.id == assessment.suggestion.episode?.id })
            assessments += assessment
    }

    private suspend fun detail(id: String): DanmakuAnime? {
        if (id in details) return details[id]
        if (requests >= MAX_DETAILS) { incomplete = true; return null }
        requests++
        val result = try { bounded("details") { api.details(id) } }
        catch (error: DanmakuException) {
            if (error.reason != DanmakuFailure.NOT_FOUND) throw error
            null
        }
        details[id] = result
        if (result != null) details[result.id] = result
        return result
    }

    private suspend fun <T> bounded(route: String, request: suspend () -> T): T? = try { request() }
    catch (error: DanmakuException) {
        if (error.reason != DanmakuFailure.TOO_LARGE) throw error
        // An unseen candidate can still outrank the complete suggestions; never auto-select a partial set.
        incomplete = true
        DanmakuDiagnostics.oversized(route)
        null
    }

    private fun markUninspected(media: DanmakuMedia, anime: DanmakuAnime) {
        val titleScore = DanmakuMatcher.titleScore(media, anime)
        val upperBound = TITLE_WEIGHT * titleScore + if (media.type == MediaType.MOVIE) MOVIE_BONUSES else EPISODE_BONUSES
        if (titleScore >= MIN_RELEVANCE) unseenUpperBound = maxOf(unseenUpperBound, upperBound)
    }

    private fun pending(decision: DanmakuDecision): DanmakuDecision {
        val suggestions = (confirmed + decision.suggestions + listOfNotNull(decision.selected))
            .distinctBy { Triple(it.anime.id, it.episode?.id, it.sourceOffsetMs) }
        return DanmakuDecision(suggestions = suggestions,
            reason = if (incomplete) DanmakuMatchReason.INCOMPLETE else DanmakuMatchReason.AMBIGUOUS)
    }

    private fun normalizeKeyword(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
        .lowercase(java.util.Locale.ROOT).trim()

    private companion object {
        const val IDENTIFICATION_TIMEOUT_MS = 15_000L
        const val MAX_SEARCH_REQUESTS = 4
        const val MAX_SEARCH_CANDIDATES = 12
        const val MAX_DETAILS = 6
        const val MAX_MATCH_DETAILS = 2
        const val MIN_RELEVANCE = 0.55
        const val AUTO_SCORE = 0.85
        const val TITLE_WEIGHT = 0.85
        const val MOVIE_BONUSES = 0.15
        const val EPISODE_BONUSES = 0.10
        const val MIN_GAP = 0.08
        const val EPSILON = 0.000000001
    }
}

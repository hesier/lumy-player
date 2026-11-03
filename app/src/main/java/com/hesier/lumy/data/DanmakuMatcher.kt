package com.hesier.lumy.data

import com.hesier.lumy.core.MediaType
import com.hesier.lumy.net.danmaku.DanmakuAnime
import com.hesier.lumy.net.danmaku.DanmakuEpisode
import java.util.Locale

/** Pure conservative matching; missing metadata never becomes evidence. */
internal object DanmakuMatcher {
    private const val TITLE_WEIGHT = 0.85
    private const val BONUS = 0.05
    private const val AUTO_TITLE = 0.90
    private const val AUTO_SCORE = 0.85
    private const val SUGGEST_TITLE = 0.55
    private const val MARGIN = 0.08
    private const val EPSILON = 0.000000001

    fun keywords(media: DanmakuMedia, title: String = media.title): List<String> {
        if (title.isBlank()) return emptyList()
        val season = media.season
        val number = media.number
        return if (media.type == MediaType.EPISODE && season != null && season >= 0 && number != null && number > 0)
            listOf(String.format(Locale.ROOT, "%s S%02dE%02d", title, season, number), title)
        else listOf(title)
    }
    fun same(left: String, right: String) = DanmakuTitleRules.folded(left).filter { it.isLetterOrDigit() } ==
        DanmakuTitleRules.folded(right).filter { it.isLetterOrDigit() }
    fun titleScore(media: DanmakuMedia, anime: DanmakuAnime): Double =
        listOfNotNull(media.title, media.originalTitle).maxOfOrNull { DanmakuTitleRules.dice(it, anime.title) } ?: 0.0
    fun episode(media: DanmakuMedia, anime: DanmakuAnime): DanmakuEpisode? =
        DanmakuEpisodeRules.resolve(media, anime).episode

    fun assess(media: DanmakuMedia, suggestion: DanmakuSuggestion): DanmakuAssessment {
        val anime = suggestion.anime
        val title = titleScore(media, anime)
        val resolution = DanmakuEpisodeRules.resolve(media, anime)
        val target = resolution.episode?.takeIf { suggestion.episode == null || suggestion.episode.id == it.id }
        val candidate = suggestion.copy(episode = suggestion.episode ?: target)
        val seasons = DanmakuTitleRules.seasons(anime.title) + listOfNotNull(resolution.season) +
            suggestion.episode?.let { DanmakuTitleRules.seasons(it.title) }.orEmpty()
        val years = DanmakuTitleRules.years(anime.title) + listOfNotNull(anime.year)
        val localSeasons = listOfNotNull(media.title, media.originalTitle).flatMap { DanmakuTitleRules.seasons(it) }.toSet()
        val kind = DanmakuEpisodeRules.kind(anime.type)
        val movie = media.type == MediaType.MOVIE
        val yearMatch = movie && media.year != null && years.singleOrNull() == media.year
        val seasonMatch = !movie && media.season != null && seasons.singleOrNull() == media.season
        val typeMatch = if (movie) kind == DanmakuEpisodeRules.Kind.MOVIE else kind == DanmakuEpisodeRules.Kind.SERIES
        val suggestedNumber = suggestion.episode?.let { DanmakuEpisodeRules.number(it)
            ?: DanmakuTitleRules.episodeNumbers(it.title).singleOrNull() }
        val forcedMismatch = (suggestion.episode != null && resolution.episode != null && suggestion.episode.id != resolution.episode.id) ||
            (!movie && media.number != null && suggestedNumber != null && suggestedNumber != media.number.toDouble())
        val conflict = forcedMismatch || years.size > 1 || seasons.size > 1 || localSeasons.size > 1 ||
            (media.season != null && localSeasons.any { it != media.season }) ||
            (media.season != null && seasons.any { it != media.season }) ||
            (movie && media.year != null && years.any { it != media.year }) || typeConflict(media, anime)
        val shortMovie = movie && DanmakuTitleRules.length(anime.title) < 3 && !yearMatch
        val laterSeason = !movie && (media.season ?: 1) > 1 && !seasonMatch
        val score = TITLE_WEIGHT * title + (if (yearMatch) BONUS else 0.0) +
            (if (seasonMatch) BONUS else 0.0) + (if (typeMatch) BONUS else 0.0)
        return DanmakuAssessment(candidate, title, if (conflict) -score else score,
            !conflict && !shortMovie && !laterSeason && target != null &&
                title + EPSILON >= AUTO_TITLE && score + EPSILON >= AUTO_SCORE,
            !conflict && resolution.missing && suggestion.episode == null)
    }
    fun decide(assessments: List<DanmakuAssessment>, incomplete: Boolean = false): DanmakuDecision {
        val ranked = assessments.filter { it.titleScore + EPSILON >= SUGGEST_TITLE }
            .sortedWith(compareByDescending<DanmakuAssessment> { it.score }.thenBy { it.suggestion.anime.id }
                .thenBy { it.suggestion.episode?.id.orEmpty() }.thenBy { it.suggestion.sourceOffsetMs })
            .distinctBy { Triple(it.suggestion.anime.id, it.suggestion.episode?.id, it.suggestion.sourceOffsetMs) }
        val suggestions = ranked.map { it.suggestion }
        val competitors = ranked.filter { it.score >= 0 && !it.definitiveMissingEpisode }
        val best = competitors.firstOrNull()
        if (!incomplete && best?.eligible == true &&
            (competitors.size == 1 || best.score - competitors[1].score + EPSILON >= MARGIN))
            return DanmakuDecision(best.suggestion, suggestions)
        if (incomplete) return DanmakuDecision(suggestions = suggestions, reason = DanmakuMatchReason.INCOMPLETE)
        if (ranked.isEmpty() || ranked.all { it.definitiveMissingEpisode }) return DanmakuDecision()
        val reason = if (competitors.size > 1 || ranked.any { it.suggestion.episode != null && !it.eligible })
            DanmakuMatchReason.AMBIGUOUS else DanmakuMatchReason.METADATA
        return DanmakuDecision(suggestions = suggestions, reason = reason)
    }
    fun confirmed(media: DanmakuMedia, suggestion: DanmakuSuggestion): Boolean {
        val target = suggestion.episode ?: return false
        val matches = suggestion.anime.episodes.filter { it.id == target.id }.distinct()
        if (matches.size != 1 || DanmakuTitleRules.trailer(suggestion.anime.title) || DanmakuTitleRules.trailer(target.title) || typeConflict(media, suggestion.anime)) return false
        if (media.type != MediaType.MOVIE) return true
        val years = DanmakuTitleRules.years(suggestion.anime.title) + listOfNotNull(suggestion.anime.year)
        return years.size <= 1 && (media.year == null || years.all { it == media.year })
    }
    private fun typeConflict(media: DanmakuMedia, anime: DanmakuAnime): Boolean {
        val kind = DanmakuEpisodeRules.kind(anime.type)
        return (media.type == MediaType.MOVIE && kind == DanmakuEpisodeRules.Kind.SERIES) ||
            (media.type == MediaType.EPISODE && kind == DanmakuEpisodeRules.Kind.MOVIE)
    }
}

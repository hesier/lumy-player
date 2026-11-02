package com.hesier.lumy.data

import com.hesier.lumy.core.MediaType
import com.hesier.lumy.net.danmaku.DanmakuAnime
import com.hesier.lumy.net.danmaku.DanmakuEpisode
import java.text.Normalizer
import java.util.Locale

/** Conservative metadata matching: an incomplete or ambiguous result requires user selection. */
internal object DanmakuMatcher {
    fun anime(media: DanmakuMedia, candidates: List<DanmakuAnime>): DanmakuAnime? {
        val titles = listOfNotNull(media.title, media.originalTitle).map(::title)
        return candidates.filter { candidate ->
            title(candidate.title) in titles && media.year != null && candidate.year == media.year &&
                when (media.type) {
                    MediaType.MOVIE -> movie(candidate.type)
                    MediaType.EPISODE -> !movie(candidate.type) && compatibleSeason(media.season, candidate.title)
                    else -> false
                }
        }.singleOrNull()
    }

    fun episode(media: DanmakuMedia, anime: DanmakuAnime): DanmakuEpisode? = when (media.type) {
        MediaType.MOVIE -> anime.episodes.singleOrNull()
        MediaType.EPISODE -> anime.episodes.filter { item ->
            val number = item.number?.toIntOrNull()
            number != null && number > 0 && number == media.number &&
                (item.seasonId.isNullOrBlank() && media.season == 1 || item.seasonId == media.season?.toString())
        }.singleOrNull()
        else -> null
    }

    fun same(left: String, right: String) = normalize(left) == normalize(right)
    private fun title(value: String) = normalize(value.replace(Regex("\\(\\d{4}\\)|（\\d{4}）"), "")
        .replace(Regex("【[^】]*】.*$|\\s+from\\s+.*$", RegexOption.IGNORE_CASE), ""))
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    private fun movie(value: String?) = value?.let { Regex("movie|电影|電影|剧场|劇場", RegexOption.IGNORE_CASE).containsMatchIn(it) } == true
    private fun compatibleSeason(season: Int?, value: String): Boolean {
        if (season == null || season < 1) return false
        val explicit = Regex("(?:S|Season\\s*)(\\d+)|第([一二三四五六七八九十0-9]+)季", RegexOption.IGNORE_CASE).find(value)
        return if (explicit == null) season == 1 else explicit.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull() == season
    }
}

package com.hesier.lumy.data

import com.hesier.lumy.core.MediaType
import com.hesier.lumy.net.danmaku.DanmakuAnime
import com.hesier.lumy.net.danmaku.DanmakuEpisode

internal data class DanmakuEpisodeResolution(val episode: DanmakuEpisode? = null,
    val missing: Boolean = false, val season: Int? = null)

internal object DanmakuEpisodeRules {
    enum class Kind { MOVIE, SERIES, SPECIAL, UNKNOWN }
    fun kind(value: String?): Kind = when (DanmakuTitleRules.folded(value.orEmpty()).trim()) {
        "movie", "电影", "電影", "剧场版", "劇場版", "theatrical", "tvmovie", "jpmovie", "tmdbmovie" -> Kind.MOVIE
        "tv", "tvseries", "tv series", "国产剧", "网络剧", "日剧", "美剧", "韩剧", "港剧", "台剧", "电视剧", "電視劇", "电视动画", "電視動畫", "番剧", "番劇", "tv动画", "tv動畫", "tv anime", "tv animation", "web series", "jpdrama", "tmdbtv" -> Kind.SERIES
        "ova", "oad", "sp", "special", "tvspecial", "特别篇", "特別篇" -> Kind.SPECIAL
        else -> Kind.UNKNOWN
    }
    fun number(episode: DanmakuEpisode): Double? = episode.number?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
    fun conflict(episode: DanmakuEpisode): Boolean {
        val parsed = DanmakuTitleRules.episodeNumbers(episode.title)
        val explicit = number(episode)
        return parsed.size > 1 || (explicit != null && parsed.any { it != explicit }) ||
            DanmakuTitleRules.seasons(episode.title).size > 1
    }
    private fun resolvedNumber(episode: DanmakuEpisode): Double? = number(episode)
        ?: DanmakuTitleRules.episodeNumbers(episode.title).singleOrNull()
    fun resolve(media: DanmakuMedia, anime: DanmakuAnime): DanmakuEpisodeResolution {
        if (anime.episodes.groupBy { it.id }.values.any { it.distinct().size > 1 }) return DanmakuEpisodeResolution()
        val episodes = anime.episodes.distinctBy { it.id }
        if (media.type == MediaType.MOVIE) return movie(episodes, anime)
        if (media.type != MediaType.EPISODE || media.number == null || media.number <= 0 ||
            media.season == 0 || listOfNotNull(media.title, media.originalTitle).any { DanmakuTitleRules.special(it) } || kind(anime.type) == Kind.SPECIAL ||
            DanmakuTitleRules.special(anime.title)) return DanmakuEpisodeResolution()
        val numbered = episodes.filter { resolvedNumber(it) == media.number.toDouble() }
        val matching = numbered.filter {
            val seasons = DanmakuTitleRules.seasons(it.title)
            seasons.isEmpty() || media.season == null || media.season in seasons
        }
        if (matching.size != 1 || matching.any { conflict(it) || DanmakuTitleRules.trailer(it.title) || DanmakuTitleRules.special(it.title) ||
                DanmakuTitleRules.special(it.number.orEmpty()) || 0 in DanmakuTitleRules.seasons(it.title) }) {
            val known = episodes.mapNotNull(::resolvedNumber)
            val missing = numbered.isEmpty() && known.size == episodes.size && known.isNotEmpty() && known.all { it > 0 && it % 1.0 == 0.0 } &&
                ((media.season ?: 1) <= 1 || media.number.toDouble() <= known.maxOrNull()!!) && episodes.none(::conflict)
            return DanmakuEpisodeResolution(missing = missing)
        }
        val selected = matching.single()
        return DanmakuEpisodeResolution(selected, season = DanmakuTitleRules.seasons(selected.title).singleOrNull())
    }
    private fun movie(episodes: List<DanmakuEpisode>, anime: DanmakuAnime): DanmakuEpisodeResolution {
        if (kind(anime.type) != Kind.MOVIE) return DanmakuEpisodeResolution()
        val main = episodes.filter { !DanmakuTitleRules.trailer(it.title) && !conflict(it) &&
            (resolvedNumber(it) == 1.0 || resolvedNumber(it) == null) }
        return DanmakuEpisodeResolution(main.singleOrNull())
    }
}

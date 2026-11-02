package com.hesier.lumy.data

import com.hesier.lumy.core.MediaType
import com.hesier.lumy.net.danmaku.DanmakuAnime
import com.hesier.lumy.net.danmaku.DanmakuEpisode
import java.text.Normalizer
import java.util.Locale

/** Automatic selection follows source order; stored manual matches are validated separately. */
internal object DanmakuMatcher {
    fun keywords(media: DanmakuMedia, title: String = media.title): List<String> {
        val season = media.season
        val number = media.number
        return if (media.type == MediaType.EPISODE && season != null && season >= 0 && number != null && number > 0)
            listOf(String.format(Locale.ROOT, "%s S%02dE%02d", title, season, number), title)
        else listOf(title)
    }

    fun anime(candidates: List<DanmakuAnime>): DanmakuAnime? = candidates.firstOrNull()

    fun episode(media: DanmakuMedia, anime: DanmakuAnime): DanmakuEpisode? = when (media.type) {
        MediaType.MOVIE -> anime.episodes.firstOrNull { number(it) == 1.0 } ?: anime.episodes.firstOrNull()
        MediaType.EPISODE -> media.number?.takeIf { it > 0 }?.let { current ->
            anime.episodes.firstOrNull { number(it) == current.toDouble() }
        }
        else -> null
    }

    private fun number(episode: DanmakuEpisode) = episode.number?.trim()?.toDoubleOrNull()
    fun same(left: String, right: String) = normalize(left) == normalize(right)
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
}

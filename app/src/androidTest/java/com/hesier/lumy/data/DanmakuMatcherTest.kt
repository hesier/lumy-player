package com.hesier.lumy.data

import com.hesier.lumy.core.MediaType
import com.hesier.lumy.net.danmaku.DanmakuAnime
import com.hesier.lumy.net.danmaku.DanmakuEpisode

internal object DanmakuMatcherTest {
    fun runAll() {
        val episode = DanmakuEpisode("e1", "First", "1", "opaque-season")
        val anime = DanmakuAnime("a1", "Different title", "Movie", 1990, listOf(episode))
        val media = DanmakuMedia("media", "server", "Name", null, MediaType.EPISODE, 2023, 3, 1, null)
        check(listOf("Name S03E01", "Name") == DanmakuMatcher.keywords(media))
        check(listOf("Name") == DanmakuMatcher.keywords(media.copy(season = null)))
        check(listOf("Original S03E01", "Original") == DanmakuMatcher.keywords(media, "Original"))
        check(anime == DanmakuMatcher.anime(listOf(anime, anime.copy(id = "a2"))))
        check(null == DanmakuMatcher.anime(emptyList()))
        check(episode == DanmakuMatcher.episode(media, anime))
        check(episode == DanmakuMatcher.episode(media.copy(year = null, season = null), anime))
        check(episode == DanmakuMatcher.episode(media, anime.copy(episodes = listOf(episode, episode.copy(id = "e2")))))
        check(null == DanmakuMatcher.episode(media.copy(number = 2), anime))
        check(null == DanmakuMatcher.episode(media.copy(number = null), anime))
        check(null == DanmakuMatcher.episode(media.copy(number = 0), anime))
        check(null == DanmakuMatcher.episode(media, anime.copy(episodes = listOf(episode.copy(number = "S1")))))
        for (number in listOf("01", "1.0", " 1 ")) {
            val numeric = episode.copy(number = number)
            check(numeric == DanmakuMatcher.episode(media, anime.copy(episodes = listOf(numeric))))
        }
        val movie = media.copy(type = MediaType.MOVIE)
        check(listOf("Name") == DanmakuMatcher.keywords(movie))
        val trailer = episode.copy(id = "trailer", number = "0")
        check(episode == DanmakuMatcher.episode(movie, anime.copy(episodes = listOf(trailer, episode))))
        check(trailer == DanmakuMatcher.episode(movie, anime.copy(episodes = listOf(trailer))))
        check(null == DanmakuMatcher.episode(movie, anime.copy(episodes = emptyList())))
    }
}

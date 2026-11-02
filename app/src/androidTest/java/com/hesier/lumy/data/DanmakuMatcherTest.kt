package com.hesier.lumy.data

import com.hesier.lumy.core.MediaType
import com.hesier.lumy.net.danmaku.DanmakuAnime
import com.hesier.lumy.net.danmaku.DanmakuEpisode

internal object DanmakuMatcherTest {
    fun runAll() {
        val episode = DanmakuEpisode("e1", "First", "1")
        val anime = DanmakuAnime("a1", "Name (2023)【日番】from site", "TV", 2023, listOf(episode))
        val media = DanmakuMedia("media", "server", "Name", null, MediaType.EPISODE, 2023, 1, 1, null)
        check(anime == DanmakuMatcher.anime(media, listOf(anime)))
        check(null == DanmakuMatcher.anime(media, listOf(anime, anime.copy(id = "a2"))))
        check(null == DanmakuMatcher.anime(media, listOf(anime.copy(year = 2024))))
        check(null == DanmakuMatcher.anime(media.copy(year = null), listOf(anime)))
        check(episode == DanmakuMatcher.episode(media, anime))
        check(null == DanmakuMatcher.episode(media, anime.copy(episodes = listOf(episode.copy(number = "S1")))))
        check(null == DanmakuMatcher.episode(media.copy(season = 2), anime))
        check(null == DanmakuMatcher.episode(media.copy(number = 2), anime))
        check(null == DanmakuMatcher.episode(media, anime.copy(episodes = listOf(episode, episode.copy(id = "e2")))))
        val movie = anime.copy(type = "Movie")
        check(movie == DanmakuMatcher.anime(media.copy(type = MediaType.MOVIE), listOf(movie)))
        check(null == DanmakuMatcher.anime(media, listOf(movie)))
        check(null == DanmakuMatcher.anime(media.copy(type = MediaType.MOVIE), listOf(anime)))
    }
}

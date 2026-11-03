package com.hesier.lumy.data

import com.hesier.lumy.core.MediaType
import com.hesier.lumy.net.danmaku.DanmakuAnime
import com.hesier.lumy.net.danmaku.DanmakuEpisode

internal object DanmakuMatcherTest {
    private val media = DanmakuMedia("media", "server", "Moon River", null,
        MediaType.EPISODE, 2023, 1, 1, null)
    private fun ep(id: String = "e1", number: String? = "1", title: String = "First") =
        DanmakuEpisode(id, title, number, "opaque-season")
    private fun anime(id: String = "a1", title: String = "Moon River", type: String? = "TV",
        episodes: List<DanmakuEpisode> = listOf(ep())) = DanmakuAnime(id, title, type, null, episodes)
    private fun decide(local: DanmakuMedia, vararg candidates: DanmakuAnime, incomplete: Boolean = false) =
        DanmakuMatcher.decide(candidates.map { DanmakuMatcher.assess(local, DanmakuSuggestion(it)) }, incomplete)
    private fun auto(case: String, local: DanmakuMedia, expected: String, vararg candidates: DanmakuAnime, expectedEpisode: String = "e1") {
        val result = decide(local, *candidates)
        check(expected == result.selected?.anime?.id) { "$case: $result" }
        check(expectedEpisode == result.selected?.episode?.id) { "$case episode: $result" }
        check(result.selected == decide(local, *candidates.reversedArray()).selected) { "$case order" }
    }
    private fun confirm(case: String, local: DanmakuMedia, vararg candidates: DanmakuAnime) {
        val result = decide(local, *candidates)
        check(null == result.selected && result.suggestions.isNotEmpty()) { "$case: $result" }
    }
    fun runAll() {
        check(DanmakuEpisodeRules.Kind.MOVIE == DanmakuEpisodeRules.kind("tmdbmovie"))
        check(DanmakuEpisodeRules.Kind.SERIES == DanmakuEpisodeRules.kind("jpdrama"))
        check(DanmakuEpisodeRules.Kind.SPECIAL == DanmakuEpisodeRules.kind("tvspecial"))
        check(DanmakuEpisodeRules.Kind.UNKNOWN == DanmakuEpisodeRules.kind("web"))
        movies()
        seasons()
        episodes()
        suggestions()
        titles()
    }
    private fun movies() {
        val movie = media.copy(type = MediaType.MOVIE)
        val right = anime("2023", type = "Movie").copy(year = 2023)
        val old = right.copy(id = "1990", year = 1990)
        auto("M01", movie, "2023", old, right)
        confirm("M02", movie.copy(year = null), old, right)
        auto("M03", movie, "2023", anime(), right)
        confirm("M04", movie, old)
        confirm("M19 trailer", movie, right.copy(episodes = listOf(ep(title = "Trailer"))))
        confirm("M19 versions", movie, right.copy(episodes = listOf(ep(), ep("other"))))
        confirm("short year required", movie.copy(title = "Up", year = null), right.copy(title = "Up", year = null))
        auto("short year known", movie.copy(title = "Up"), "2023", right.copy(title = "Up"))
        confirm("internal year", movie, right.copy(title = "Moon River (1990)"))
    }
    private fun seasons() {
        val second = media.copy(season = 2)
        auto("M05", second, "second", anime("first", "Moon River Season 1"), anime("second", "Moon River 第二季"))
        confirm("M06", second, anime())
        confirm("M11 S00", media.copy(season = 0), anime())
        confirm("M11 OVA", media.copy(title = "Moon River OVA"), anime(title = "Moon River OVA"))
        confirm("M12", second.copy(number = 25), anime(title = "Moon River S02", episodes = listOf(ep(), ep("e2", "2"))))
        confirm("M18", second, anime(title = "Moon River S02", episodes = listOf(ep(title = "S02E02"))))
        confirm("local season conflict", second.copy(title = "Moon River S01"), anime(title = "Moon River S02"))
        auto("M24", media.copy(year = 2025), "a1", anime().copy(year = 2020))
        auto("explicit episode season", second, "a1", anime(episodes = listOf(ep(title = "S02E01")) ))
        check(setOf(12) == DanmakuTitleRules.seasons("Moon River 第十二期"))
        check(setOf(99) == DanmakuTitleRules.seasons("Moon River 第九十九季"))
    }
    private fun episodes() {
        val third = media.copy(number = 3)
        auto("M07", third, "has3", anime("missing"), anime("has3", episodes = listOf(ep("three", "3"))), expectedEpisode = "three")
        val sparse = anime(episodes = listOf(ep(), ep("3", "3"), ep("4", "4")))
        check(DanmakuMatchReason.NO_MATCH == decide(media.copy(number = 2), sparse).reason) { "M08" }
        for (number in listOf("01", "1.0", " 1 ")) {
            val target = ep(number = number)
            check(target == DanmakuMatcher.episode(media, anime(episodes = listOf(ep("3", "3"), target)))) { "M09" }
        }
        confirm("M10", media, anime(episodes = listOf(ep(), ep("other"))))
        confirm("conflicting duplicate ID", media, anime(episodes = listOf(ep(), ep(number = "2"))))
        confirm("M22 local", media.copy(number = null), anime())
        confirm("M22 source", media, anime(episodes = listOf(ep(number = null))))
        auto("parsed EP", media, "a1", anime(episodes = listOf(ep(number = null, title = "EP 1"))))
        auto("parsed Chinese", media, "a1", anime(episodes = listOf(ep(number = null, title = "第1话"))))
        val bySeason = anime(episodes = listOf(ep(title = "S01E01"), ep("other", title = "S02E01")))
        auto("duplicate separated by season", media, "a1", bySeason)
        check("e1" == DanmakuMatcher.episode(media, anime(episodes = listOf(ep(), ep())))?.id)
    }
    private fun suggestions() {
        val shifted = DanmakuSuggestion(anime(title = "Unrelated Season 4"), ep("different", "99"), -1250)
        val details = shifted.copy(anime = shifted.anime.copy(episodes = listOf(shifted.episode!!)))
        check(DanmakuMatcher.confirmed(media, details)) { "M13" }
        check(!DanmakuMatcher.assess(media, details).eligible) { "M15" }
        check(null == decide(media, anime(), anime("other")).selected) { "M16" }
        auto("M17", media.copy(year = null), "a1", anime(type = "unknown"))
        confirm("source S00", media.copy(season = null), anime(episodes = listOf(ep(title = "S00E01"))))
        confirm("fractional source", media, anime(episodes = listOf(ep(number = "1.5"))))
        check(null == decide(media, anime(), incomplete = true).selected) { "M21" }
        val match = DanmakuSuggestion(anime(), ep())
        val evidence = DanmakuMatcher.assess(media, match)
        check(match == DanmakuMatcher.decide(listOf(evidence, evidence)).selected)
        check(null == DanmakuMatcher.decide(listOf(evidence, evidence.copy(
            suggestion = match.copy(sourceOffsetMs = 250)))).selected) { "shift ambiguity" }
        check(!DanmakuMatcher.assess(media, match.copy(episode = ep("wrong", "2"))).eligible)
        check(!DanmakuMatcher.confirmed(media, match.copy(episode = ep("absent"))))
        val border = evidence.copy(score = 0.85, titleScore = 0.90)
        check(match == DanmakuMatcher.decide(listOf(border)).selected)
        val competitor = border.copy(score = 0.77, suggestion = match.copy(anime = anime("other")))
        check(match == DanmakuMatcher.decide(listOf(border, competitor)).selected)
        check(null == DanmakuMatcher.decide(listOf(border, competitor.copy(score = 0.77001))).selected)
    }
    private fun titles() {
        for (suffix in listOf("(2023)【国产剧】from tencent", "(2023)【电视剧】from 360", "（来源：自定义服务）")) {
            check(1.0 == DanmakuTitleRules.dice("三体", "三体$suffix")) { "Source annotation: $suffix" }
        }
        check(setOf(2023) == DanmakuTitleRules.years("作品（２０２３）"))
        check("fromdusktilldawn" == DanmakuTitleRules.normalized("From Dusk Till Dawn"))
        check("作品未知标签" == DanmakuTitleRules.normalized("作品【未知标签】"))

        auto("M20 alias", media.copy(title = "月河", originalTitle = "Ｍｏｏｎ　Ｒｉｖｅｒ"), "a1", anime())
        check(1.0 == DanmakuTitleRules.dice("Moon River (2023) [Bilibili]", "Moon River"))
        check(!DanmakuMatcher.same("Moon River 2", "Moon River"))
        check(!DanmakuMatcher.same("Moon River OVA", "Moon River"))
        check(!DanmakuMatcher.same("Moon River (The Return)", "Moon River"))
        check(0.0 == DanmakuTitleRules.dice("月河", "月何"))
        check(0.5 == DanmakuTitleRules.dice("𠀀𠀁𠀂", "𠀀𠀁𠀃"))
        check(listOf("Moon River S01E01", "Moon River") == DanmakuMatcher.keywords(media))
        check(listOf("Moon River S00E01", "Moon River") == DanmakuMatcher.keywords(media.copy(season = 0)))
        check(emptyList<String>() == DanmakuMatcher.keywords(media, "  "))
    }
}

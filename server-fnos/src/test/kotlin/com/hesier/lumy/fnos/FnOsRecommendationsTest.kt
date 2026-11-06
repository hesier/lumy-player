package com.hesier.lumy.fnos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FnOsRecommendationsTest {
    private val actor = PersonDto("actor-1", "Alice", type = "Actor")
    private val source = BaseItem("source-file", "Source", type = "Movie", productionYear = 2000,
        genres = listOf("Action"), people = listOf(actor), mediaSources = listOf(MediaSourceDto("native-source")))

    @Test
    fun `exclude source versions other media types and unrelated items`() {
        val related = source.copy(id = "related", mediaSources = emptyList())
        val candidates = listOf(
            source,
            related.copy(id = "other-source-file", mediaSources = source.mediaSources),
            related.copy(id = "native-source"),
            related.copy(id = "same-file-reference", mediaSources = listOf(MediaSourceDto(source.id))),
            related.copy(id = "series", type = "Series"),
            related.copy(id = "episode", type = "Episode"),
            related.copy(id = "unrelated", genres = listOf("Drama"), people = emptyList()),
            related,
        )
        assertEquals(listOf("related"), fnOsRecommendations(source, candidates).map { it.id })
    }

    @Test
    fun `rank shared cast and genres before year distance`() {
        val moreGenres = source.copy(genres = listOf("Action", "Adventure"))
        val candidates = listOf(
            BaseItem("genre", type = "Movie", productionYear = 2000, genres = listOf("Action")),
            BaseItem("actor", type = "Movie", productionYear = 2001, people = listOf(actor)),
            BaseItem("both", type = "Movie", productionYear = 1990, genres = listOf("Action"), people = listOf(actor)),
            BaseItem("best", type = "Movie", productionYear = 1980, genres = moreGenres.genres, people = listOf(actor)),
        )
        assertEquals(listOf("best", "both", "genre", "actor"), fnOsRecommendations(moreGenres, candidates).map { it.id })
    }

    @Test
    fun `unknown years rank after known years and title then id break ties`() {
        val candidates = listOf(
            BaseItem("unknown", "A", type = "Movie", genres = listOf("Action")),
            BaseItem("z", "Zulu", type = "Movie", productionYear = 1999, genres = listOf("Action")),
            BaseItem("a2", "Alpha", type = "Movie", productionYear = 2001, genres = listOf("Action")),
            BaseItem("a1", "Alpha", type = "Movie", productionYear = 1999, genres = listOf("Action")),
        )
        val expected = listOf("a1", "a2", "z", "unknown")
        assertEquals(expected, fnOsRecommendations(source, candidates).map { it.id })
        assertEquals(expected, fnOsRecommendations(source, candidates.reversed()).map { it.id })
    }

    @Test
    fun `series recommendations remain series only`() {
        val series = source.copy(type = "Series")
        assertEquals(listOf("series"), fnOsRecommendations(series, listOf(
            BaseItem("movie", type = "Movie", genres = source.genres),
            BaseItem("series", type = "Series", genres = source.genres),
        )).map { it.id })
        assertTrue(fnOsRecommendations(source.copy(type = "Episode"), listOf(source)).isEmpty())
    }

    @Test
    fun `empty metadata and director only matches do not invent recommendations`() {
        assertTrue(fnOsRecommendations(BaseItem("empty", type = "Movie"), listOf(source)).isEmpty())
        val directorOnly = source.copy(genres = listOf("", " "), people = listOf(actor.copy(type = "Director")))
        assertTrue(fnOsRecommendations(directorOnly, listOf(source)).isEmpty())
        val blankActors = source.copy(genres = emptyList(), people = listOf(PersonDto("", " ", type = "Actor")))
        assertTrue(fnOsRecommendations(blankActors, listOf(blankActors.copy(id = "other"))).isEmpty())
    }

    @Test
    fun `actor id or normalized name and normalized genres can establish connection`() {
        val candidates = listOf(
            BaseItem("same-id", type = "Movie", people = listOf(actor.copy(name = "Localized"))),
            BaseItem("same-name", type = "Movie", people = listOf(actor.copy(id = "different", name = " ALICE "))),
            BaseItem("same-genre", type = "Movie", genres = listOf(" action ")),
        )
        assertEquals(setOf("same-id", "same-name", "same-genre"), fnOsRecommendations(source, candidates).map { it.id }.toSet())
    }

    @Test
    fun `duplicate metadata does not inflate relevance`() {
        val duplicateSource = source.copy(genres = listOf("Action", "action"), people = listOf(actor, actor))
        val candidates = listOf(
            BaseItem("one", "A", type = "Movie", genres = listOf("Action", "Action")),
            BaseItem("both", "B", type = "Movie", genres = listOf("Action"), people = listOf(actor)),
        )
        assertEquals(listOf("both", "one"), fnOsRecommendations(duplicateSource, candidates).map { it.id })
    }

    @Test
    fun `keep at most twenty deterministic unique movies across native versions`() {
        val candidates = (0..24).map { i ->
            BaseItem("file-$i", "Movie ${i.toString().padStart(2, '0')}", type = "Movie", genres = listOf("Action"),
                mediaSources = listOf(MediaSourceDto("native-$i")))
        }
        val duplicate = candidates.first().copy(id = "alternate-file", name = "ZZZ")
        val result = fnOsRecommendations(source, candidates.reversed() + duplicate + candidates.first())
        assertEquals(candidates.take(20).map { it.id }, result.map { it.id })
        assertEquals(20, result.size)
    }
}

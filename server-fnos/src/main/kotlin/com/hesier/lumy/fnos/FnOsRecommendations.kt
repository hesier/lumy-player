package com.hesier.lumy.fnos

import java.util.Locale
import kotlin.math.abs

/** Rank only catalog entries with an actual metadata connection to the source. */
internal fun fnOsRecommendations(source: BaseItem, catalog: List<BaseItem>): List<BaseItem> {
    if (source.type != "Movie" && source.type != "Series") return emptyList()
    val actors = source.actors()
    val genres = source.genreKeys()
    if (actors.isEmpty() && genres.isEmpty()) return emptyList()
    val sourceIds = source.versionIds()
    val ranked = catalog.asSequence()
        .filter { it.type == source.type && it.versionIds().none(sourceIds::contains) }
        .map { candidate ->
            val cast = candidate.actors()
            val overlap = actors.count { actor -> cast.any { actor.matches(it) } } +
                genres.intersect(candidate.genreKeys()).size
            Recommendation(candidate, overlap, yearDistance(source, candidate))
        }
        .filter { it.overlap > 0 }
        .sortedWith(compareByDescending<Recommendation> { it.overlap }
            .thenBy { it.yearDistance }
            .thenBy { (it.item.sortName ?: it.item.name).normalized() }
            .thenBy { it.item.id })
    val seen = sourceIds.toMutableSet()
    return ranked.filter { recommendation ->
        val ids = recommendation.item.versionIds()
        if (ids.any(seen::contains)) false else { seen.addAll(ids); true }
    }.take(RECOMMENDATION_LIMIT).map { it.item }.toList()
}

private data class Recommendation(val item: BaseItem, val overlap: Int, val yearDistance: Long)

private fun BaseItem.versionIds(): Set<String> =
    (listOf(id) + mediaSources.map { it.id }).filter { it.isNotBlank() }.toSet()

private fun BaseItem.genreKeys(): Set<String> = genres.map { it.normalized() }.filter { it.isNotEmpty() }.toSet()

private fun BaseItem.actors(): List<PersonDto> = people.filter {
    it.type == "Actor" && (it.id.isNotBlank() || it.name.isNotBlank())
}.distinctBy { it.id.takeIf(String::isNotBlank) ?: it.name.normalized() }

private fun PersonDto.matches(other: PersonDto): Boolean =
    (id.isNotBlank() && id == other.id) ||
        (name.isNotBlank() && name.normalized() == other.name.normalized())

private fun String.normalized(): String = trim().lowercase(Locale.ROOT)

private fun yearDistance(source: BaseItem, candidate: BaseItem): Long {
    val sourceYear = source.productionYear ?: return Long.MAX_VALUE
    val candidateYear = candidate.productionYear ?: return Long.MAX_VALUE
    return abs(sourceYear.toLong() - candidateYear.toLong())
}

private const val RECOMMENDATION_LIMIT = 20

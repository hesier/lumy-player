package com.hesier.lumy.fnos

import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class FnOsNextUp(private val children: suspend (String) -> List<MediaItem>) {
    suspend fun items(history: List<MediaItem>, limit: Int = DEFAULT_LIMIT): List<MediaItem> = coroutineScope {
        require(limit >= 0)
        if (limit == 0) return@coroutineScope emptyList()
        val shows = history.filter { it.type == MediaType.EPISODE && it.grandparentId != null }
            .filter { it.watched || (it.viewOffsetMs ?: 0) > 0 }
            .groupBy { it.grandparentId!! }.entries.take(MAX_SHOWS)
        val semaphore = Semaphore(CONCURRENCY)
        shows.map { (showId, records) ->
            async {
                semaphore.withPermit {
                    try {
                        next(showId, records)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A removed episode or an unavailable show must not hide other recommendations.
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull().distinctBy { it.id }.take(limit)
    }

    private suspend fun next(showId: String, records: List<MediaItem>): MediaItem? {
        val anchor = records.first()
        // Continue Watching retains the anchor; Next Up starts strictly after it.
        val seasons = children(showId).filter { it.type == MediaType.SEASON }
            .distinctBy { it.id }.sortedBy { it.seasonNumber ?: Int.MAX_VALUE }
        val current = seasons.indexOfFirst { it.id == anchor.parentId }
        if (current < 0) return null
        val special = anchor.seasonNumber == 0 || seasons[current].seasonNumber == 0
        val following = seasons.drop(current).filter { season ->
            if (special) season.id == anchor.parentId else season.seasonNumber != 0
        }.take(MAX_SEASONS)
        val history = records.asReversed().associateBy { it.id }
        for (season in following) {
            val episodes = children(season.id).filter { it.type == MediaType.EPISODE }
                .distinctBy { it.id }.sortedBy { it.episodeNumber ?: Int.MAX_VALUE }
            val candidates = if (season.id == anchor.parentId) episodes.after(anchor) else episodes
            for (episode in candidates) {
                val state = history[episode.id] ?: episode
                if (state.watched) continue
                if ((state.viewOffsetMs ?: 0) > 0) return null
                return episode
            }
        }
        return null
    }

    private fun List<MediaItem>.after(anchor: MediaItem): List<MediaItem> {
        val index = indexOfFirst { it.id == anchor.id }
        if (index >= 0) return drop(index + 1)
        val number = anchor.episodeNumber ?: return emptyList()
        return filter { (it.episodeNumber ?: Int.MIN_VALUE) > number }
    }

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_SHOWS = 40
        const val MAX_SEASONS = 30
        const val CONCURRENCY = 4
    }
}

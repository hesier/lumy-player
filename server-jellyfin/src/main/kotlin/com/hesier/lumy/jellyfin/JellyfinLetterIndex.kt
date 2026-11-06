package com.hesier.lumy.jellyfin

import com.hesier.lumy.core.LetterBucket
import com.hesier.lumy.core.LibraryQuery
import com.hesier.lumy.core.SortOrder
import com.hesier.lumy.core.WatchFilter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

/** Lightweight DTOs keep indexing independent of detail and login payloads. */
@Serializable
internal data class LetterIndexResult(
    val items: List<LetterIndexItem> = emptyList(),
    val totalRecordCount: Int? = null,
)

@Serializable
internal data class LetterIndexItem(
    val id: String = "",
    val name: String = "",
    val sortName: String? = null,
)

internal class JellyfinLetterIndex(
    private val fetch: suspend (String, LibraryQuery, Int) -> LetterIndexResult,
) {
    private data class Key(val libraryId: String, val query: LibraryQuery)
    private data class Cached(val buckets: List<LetterBucket>, val created: Long)
    private val cache = LinkedHashMap<Key, Cached>(CACHE_SIZE, 0.75f, true)
    private var generation = 0L

    suspend fun get(libraryId: String, query: LibraryQuery): List<LetterBucket> {
        currentCoroutineContext().ensureActive()
        if (query.sort != SortOrder.TITLE) return emptyList()
        val key = Key(libraryId, query)
        val version: Long
        synchronized(cache) {
            cache[key]?.takeIf { System.nanoTime() - it.created < CACHE_TTL_NS }?.let { return it.buckets }
            version = generation
        }
        val buckets = scan(libraryId, query) ?: return emptyList()
        currentCoroutineContext().ensureActive()
        synchronized(cache) {
            // A watched mutation must also invalidate a scan already in flight.
            if (version == generation) {
                cache[key] = Cached(buckets, System.nanoTime())
                if (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
            }
        }
        return buckets
    }

    fun invalidateWatched() = synchronized(cache) {
        generation++
        cache.keys.removeAll { it.query.watch != WatchFilter.ALL }
    }

    private suspend fun scan(libraryId: String, query: LibraryQuery): List<LetterBucket>? {
        val buckets = mutableListOf<LetterBucket>()
        var offset = 0
        var total: Int? = null
        var previous: List<LetterIndexItem>? = null
        while (offset < MAX_ITEMS) {
            currentCoroutineContext().ensureActive()
            val page = fetch(libraryId, query, offset)
            currentCoroutineContext().ensureActive()
            val count = page.totalRecordCount
            if (count != null && (count < 0 || count > MAX_ITEMS || total != null && total != count)) return null
            total = count ?: total
            if (page.items.isEmpty()) return buckets.takeIf { total == null || offset == total }
            if (page.items.size > PAGE_SIZE || page.items == previous) return null
            if (offset + page.items.size > (total ?: MAX_ITEMS)) return null
            for (item in page.items) append(buckets, item)
            offset += page.items.size
            if (offset == total) return buckets
            previous = page.items
        }
        // Never expose partial counts: their cumulative offsets would be misleading.
        return null
    }

    private fun append(buckets: MutableList<LetterBucket>, item: LetterIndexItem) {
        val title = item.sortName?.takeIf { it.isNotBlank() } ?: item.name
        val point = title.trim().takeIf { it.isNotEmpty() }?.codePointAt(0)
        val label = if (point != null && Character.isLetter(point)) {
            String(Character.toChars(Character.toUpperCase(point)))
        } else {
            "#"
        }
        val last = buckets.lastOrNull()
        if (last?.label == label) buckets[buckets.lastIndex] = last.copy(count = last.count + 1)
        else buckets += LetterBucket(label, 1)
    }

    companion object {
        const val PAGE_SIZE = 500
        private const val MAX_ITEMS = 100_000
        private const val CACHE_SIZE = 8
        private const val CACHE_TTL_NS = 60_000_000_000L
    }
}

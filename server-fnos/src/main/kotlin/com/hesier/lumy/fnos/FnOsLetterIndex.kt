package com.hesier.lumy.fnos

import com.hesier.lumy.core.LetterBucket
import com.hesier.lumy.core.LibraryQuery
import com.hesier.lumy.core.Page
import com.hesier.lumy.core.SortOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class FnOsLetterIndex(
    private val fetch: suspend (Int, Int, LibraryQuery) -> Page<BaseItem>,
) {
    suspend fun get(query: LibraryQuery): List<LetterBucket> {
        currentCoroutineContext().ensureActive()
        if (query.sort != SortOrder.TITLE) return emptyList()
        val buckets = mutableListOf<LetterBucket>()
        val seen = HashSet<String>()
        var offset = 0
        var total: Int? = null
        while (offset < MAX_ITEMS) {
            currentCoroutineContext().ensureActive()
            val page = fetch(offset, PAGE_SIZE, query)
            currentCoroutineContext().ensureActive()
            if (page.offset != offset || page.total !in 0..MAX_ITEMS) return emptyList()
            if (total != null && total != page.total) return emptyList()
            total = page.total
            // A dropped compatibility match would shift all later jump offsets.
            if (page.items.size != minOf(PAGE_SIZE, page.total - offset)) return emptyList()
            for (item in page.items) {
                if (!seen.add(item.id)) return emptyList()
                append(buckets, item)
            }
            offset += page.items.size
            if (offset == page.total) return buckets
        }
        return emptyList()
    }

    private fun append(buckets: MutableList<LetterBucket>, item: BaseItem) {
        val title = (item.sortName ?: item.name).trim()
        val point = title.takeIf { it.isNotEmpty() }?.codePointAt(0)
        val label = if (point != null && Character.isLetter(point)) {
            String(Character.toChars(Character.toUpperCase(point)))
        } else {
            "#"
        }
        val last = buckets.lastOrNull()
        if (last?.label == label) buckets[buckets.lastIndex] = last.copy(count = last.count + 1)
        else buckets += LetterBucket(label, 1)
    }

    private companion object {
        const val PAGE_SIZE = 500
        const val MAX_ITEMS = 100_000
    }
}

package com.hesier.lumy.fnos

import com.hesier.lumy.core.LetterBucket
import com.hesier.lumy.core.LibraryQuery
import com.hesier.lumy.core.Page
import com.hesier.lumy.core.ResolutionFilter
import com.hesier.lumy.core.SortOrder
import com.hesier.lumy.core.WatchFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FnOsLetterIndexTest {
    @Test
    fun `pages preserve actual item order and adjacent buckets across boundaries`() = runBlocking {
        val offsets = mutableListOf<Int>()
        val index = FnOsLetterIndex { offset, limit, _ ->
            offsets += offset
            assertEquals(500, limit)
            if (offset == 0) Page(List(500) { BaseItem("$it", "Banana") }, offset, 504)
            else Page(listOf(
                BaseItem("500", "Bravo"), BaseItem("501", "Apple"),
                BaseItem("502", "Blue"), BaseItem("503", "Berry"),
            ), offset, 504)
        }
        assertEquals(listOf(LetterBucket("B", 501), LetterBucket("A", 1), LetterBucket("B", 2)), index.get(LibraryQuery()))
        assertEquals(listOf(0, 500), offsets)
    }

    @Test
    fun `all filters are forwarded unchanged for every page`() = runBlocking {
        val query = LibraryQuery(watch = WatchFilter.UNWATCHED, genreId = "2", resolution = ResolutionFilter.FHD_1080)
        var calls = 0
        val index = FnOsLetterIndex { offset, _, actual ->
            calls++
            assertEquals(query, actual)
            Page(List(minOf(500, 501 - offset)) { BaseItem("${offset + it}", "Alpha") }, offset, 501)
        }
        assertEquals(listOf(LetterBucket("A", 501)), index.get(query))
        assertEquals(2, calls)
    }

    @Test
    fun `sort names unicode letters and nonletters determine labels`() = runBlocking {
        val index = FnOsLetterIndex { offset, _, _ -> Page(listOf(
            BaseItem("1", "Zulu", sortName = "alpha"), BaseItem("2", "Apple"),
            BaseItem("3", "42"), BaseItem("4", "!"), BaseItem("5", "éclair"),
        ), offset, 5) }
        assertEquals(listOf(LetterBucket("A", 2), LetterBucket("#", 2), LetterBucket("É", 1)), index.get(LibraryQuery()))
    }

    @Test
    fun `non title sorts never fetch`() = runBlocking {
        val index = FnOsLetterIndex { _, _, _ -> error("must not fetch") }
        for (sort in SortOrder.entries.filter { it != SortOrder.TITLE }) {
            assertTrue(index.get(LibraryQuery(sort = sort)).isEmpty())
        }
    }

    @Test
    fun `mapping drops invalid totals and mismatched offsets hide the index`() = runBlocking {
        val pages = listOf(
            Page(listOf(BaseItem("1", "Alpha")), 0, 2),
            Page(emptyList(), 0, -1),
            Page(emptyList(), 0, 100_001),
            Page(listOf(BaseItem("1", "Alpha")), 1, 1),
            Page(emptyList(), 0, 1),
            Page(listOf(BaseItem("1", "Alpha"), BaseItem("2", "Beta")), 0, 1),
        )
        for (page in pages) {
            val index = FnOsLetterIndex { _, _, _ -> page }
            assertEquals(emptyList<LetterBucket>(), index.get(LibraryQuery()))
        }
    }

    @Test
    fun `changing totals or repeated item ids discard completed prefix`() = runBlocking {
        for (changeTotal in listOf(false, true)) {
            var calls = 0
            val index = FnOsLetterIndex { offset, _, _ ->
                calls++
                if (offset == 0) Page(List(500) { BaseItem("$it", "Alpha") }, 0, 501)
                else Page(listOf(BaseItem("1", "Alpha")), offset, if (changeTotal) 502 else 501)
            }
            assertEquals(emptyList<LetterBucket>(), index.get(LibraryQuery()))
            assertEquals(2, calls)
        }
    }

    @Test
    fun `bounded scan accepts exactly one hundred thousand items`() = runBlocking {
        var calls = 0
        val index = FnOsLetterIndex { offset, _, _ ->
            calls++
            Page(List(500) { BaseItem("${offset + it}", "Alpha") }, offset, 100_000)
        }
        assertEquals(listOf(LetterBucket("A", 100_000)), index.get(LibraryQuery()))
        assertEquals(200, calls)
    }

    @Test
    fun `empty catalog returns an empty index with one request`() = runBlocking {
        var calls = 0
        val index = FnOsLetterIndex { offset, _, _ -> calls++; Page(emptyList(), offset, 0) }
        assertTrue(index.get(LibraryQuery()).isEmpty())
        assertEquals(1, calls)
    }

    @Test
    fun `cancelled scan propagates cancellation after a completed page`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val index = FnOsLetterIndex { offset, _, _ ->
            calls++
            if (offset == 0) Page(List(500) { BaseItem("$it", "Alpha") }, offset, 501)
            else { entered.complete(Unit); awaitCancellation() }
        }
        val pending = async { index.get(LibraryQuery()) }
        entered.await()
        pending.cancelAndJoin()
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        assertEquals(2, calls)
    }
}

package com.hesier.lumy.jellyfin

import com.hesier.lumy.core.LetterBucket
import com.hesier.lumy.core.LibraryQuery
import com.hesier.lumy.core.ResolutionFilter
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import com.hesier.lumy.core.SortOrder
import com.hesier.lumy.core.WatchFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterIndexTest {
    private val web = MockWebServer()
    private val client = JellyfinClient(JellyfinClientInfo("Lumy", "1", "dev1", "Box"))

    @After
    fun tearDown() = web.shutdown()

    private fun server(kind: ServerKind) = JellyfinMediaServer(
        ServerSession(kind, "srv", "NAS", web.url("/").toString().trimEnd('/'), "tok", userId = "u1"),
        client,
    )

    private fun page(vararg names: String, total: Int? = names.size): LetterIndexResult = LetterIndexResult(
        names.mapIndexed { i, name -> LetterIndexItem("$i$name", name) }, total,
    )

    @Test
    fun `both servers reuse browse filters and request only lightweight fields`() = runBlocking {
        for (kind in listOf(ServerKind.JELLYFIN, ServerKind.EMBY)) {
            val server = server(kind)
            val query = LibraryQuery(watch = WatchFilter.UNWATCHED, genreId = "g1", resolution = ResolutionFilter.FHD_1080)
            web.enqueue(MockResponse().setBody("""{"Items":[],"TotalRecordCount":0}"""))
            server.libraryItems("lib", 0, 500, query)
            val browse = web.takeRequest().requestUrl!!
            web.enqueue(MockResponse().setBody("""{"Items":[{"Id":"1","Name":"The Zebra","SortName":"alpha"}],"TotalRecordCount":1}"""))
            assertEquals(listOf(LetterBucket("A", 1)), server.letterIndex("lib", query))
            val index = web.takeRequest().requestUrl!!
            for (param in listOf("UserId", "ParentId", "Recursive", "IncludeItemTypes", "SortBy", "SortOrder", "Filters", "GenreIds", "MinWidth", "MaxWidth")) {
                assertEquals(browse.queryParameter(param), index.queryParameter(param))
            }
            assertEquals("SortName", index.queryParameter("Fields"))
            assertEquals("false", index.queryParameter("EnableImages"))
            assertEquals("false", index.queryParameter("EnableUserData"))
            assertEquals("true", index.queryParameter("EnableTotalRecordCount"))
        }
    }

    @Test
    fun `pagination preserves adjacent buckets and server order across pages`() = runBlocking {
        val offsets = mutableListOf<Int>()
        val index = JellyfinLetterIndex { _, _, offset ->
            offsets += offset
            when (offset) {
                0 -> LetterIndexResult(List(500) { LetterIndexItem("$it", "B title") }, 503)
                else -> page("Banana", "Apple", "Blue", total = 503)
            }
        }
        assertEquals(listOf(LetterBucket("B", 501), LetterBucket("A", 1), LetterBucket("B", 1)), index.get("lib", LibraryQuery()))
        assertEquals(listOf(0, 500), offsets)
    }

    @Test
    fun `missing totals scan to empty page even with server capped pages`() = runBlocking {
        val offsets = mutableListOf<Int>()
        val index = JellyfinLetterIndex { _, _, offset ->
            offsets += offset
            when (offset) {
                0 -> page("Alpha", "Beta", total = null)
                2 -> page("Bravo", total = null)
                else -> page(total = null)
            }
        }
        assertEquals(listOf(LetterBucket("A", 1), LetterBucket("B", 2)), index.get("lib", LibraryQuery()))
        assertEquals(listOf(0, 2, 3), offsets)
    }

    @Test
    fun `name fallback unicode and nonletters keep exact counts`() = runBlocking {
        val index = JellyfinLetterIndex { _, _, _ ->
            LetterIndexResult(listOf(
                LetterIndexItem("1", "Zulu", "alpha"),
                LetterIndexItem("2", "apple", " "),
                LetterIndexItem("3", "42"),
                LetterIndexItem("4", "!"),
                LetterIndexItem("5", "éclair"),
                LetterIndexItem("6", "中文"),
            ), 6)
        }
        assertEquals(listOf(LetterBucket("A", 2), LetterBucket("#", 2), LetterBucket("É", 1), LetterBucket("中", 1)), index.get("lib", LibraryQuery()))
    }

    @Test
    fun `unreliable counts and repeated pages never expose partial offsets`() = runBlocking {
        val scenarios = listOf(
            listOf(page("Alpha", total = 2), page(total = 2)),
            listOf(page("Alpha", total = 2), page("Beta", total = 3)),
            listOf(page("Alpha", total = 0)),
            listOf(page("Alpha", total = 100_001)),
            listOf(page("Alpha", total = null), page("Alpha", total = null)),
        )
        for (pages in scenarios) {
            var calls = 0
            val index = JellyfinLetterIndex { _, _, _ -> pages[calls++] }
            assertEquals(emptyList<LetterBucket>(), index.get("lib", LibraryQuery()))
            assertTrue(calls <= 2)
        }
    }

    @Test
    fun `missing totals remain bounded when the server never ends pagination`() = runBlocking {
        var calls = 0
        val index = JellyfinLetterIndex { _, _, offset ->
            calls++
            LetterIndexResult(List(500) { LetterIndexItem("$offset-$it", "Alpha") })
        }
        assertEquals(emptyList<LetterBucket>(), index.get("lib", LibraryQuery()))
        assertEquals(200, calls)
    }

    @Test
    fun `non title sort never requests an index`() = runBlocking {
        val index = JellyfinLetterIndex { _, _, _ -> error("must not fetch") }
        for (sort in SortOrder.entries.filter { it != SortOrder.TITLE }) {
            assertEquals(emptyList<LetterBucket>(), index.get("lib", LibraryQuery(sort = sort)))
        }
    }

    @Test
    fun `cache keys include all filters and evict oldest entries`() = runBlocking {
        var calls = 0
        val index = JellyfinLetterIndex { _, _, _ -> calls++; page("Alpha") }
        val queries = listOf(
            LibraryQuery(), LibraryQuery(watch = WatchFilter.WATCHED),
            LibraryQuery(genreId = "g"), LibraryQuery(resolution = ResolutionFilter.SD),
        )
        for (query in queries) repeat(2) { index.get("lib", query) }
        assertEquals(4, calls)
        repeat(8) { index.get("lib$it", LibraryQuery()) }
        assertEquals(12, calls)
        index.get("lib", LibraryQuery())
        assertEquals(13, calls)
    }

    @Test
    fun `successful watched mutation invalidates filtered indexes`() = runBlocking {
        for (kind in listOf(ServerKind.JELLYFIN, ServerKind.EMBY)) {
            val server = server(kind)
            val query = LibraryQuery(watch = WatchFilter.WATCHED)
            web.enqueue(MockResponse().setBody("""{"Items":[{"Id":"1","Name":"Alpha"}],"TotalRecordCount":1}"""))
            server.letterIndex("lib", query)
            server.letterIndex("lib", query)
            web.enqueue(MockResponse().setResponseCode(204))
            server.setWatched("1", false)
            web.enqueue(MockResponse().setBody("""{"Items":[],"TotalRecordCount":0}"""))
            assertEquals(emptyList<LetterBucket>(), server.letterIndex("lib", query))
            repeat(3) { web.takeRequest() }
        }
        assertEquals(6, web.requestCount)
    }

    @Test
    fun `mutation during scan prevents stale cache insertion`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val index = JellyfinLetterIndex { _, _, _ ->
            calls++
            if (calls == 1) { entered.complete(Unit); release.await() }
            page("Alpha")
        }
        val query = LibraryQuery(watch = WatchFilter.UNWATCHED)
        val pending = async { index.get("lib", query) }
        entered.await()
        index.invalidateWatched()
        release.complete(Unit)
        pending.await()
        index.get("lib", query)
        assertEquals(2, calls)
    }

    @Test
    fun `cancelled scan propagates cancellation without caching partial buckets`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val index = JellyfinLetterIndex { _, _, _ ->
            calls++
            if (calls == 1) return@JellyfinLetterIndex page("Alpha", total = 2)
            if (calls == 2) { entered.complete(Unit); awaitCancellation() }
            page("Beta")
        }
        val pending = async { index.get("lib", LibraryQuery()) }
        entered.await()
        pending.cancelAndJoin()
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        assertEquals(listOf(LetterBucket("B", 1)), index.get("lib", LibraryQuery()))
        assertEquals(3, calls)
    }

    @Test
    fun `fetch errors propagate and can be retried`() = runBlocking {
        var calls = 0
        val index = JellyfinLetterIndex { _, _, _ ->
            if (calls++ == 0) throw JellyfinHttpException(500, "failed")
            page("Alpha")
        }
        assertTrue(runCatching { index.get("lib", LibraryQuery()) }.exceptionOrNull() is JellyfinHttpException)
        assertEquals(listOf(LetterBucket("A", 1)), index.get("lib", LibraryQuery()))
    }
}

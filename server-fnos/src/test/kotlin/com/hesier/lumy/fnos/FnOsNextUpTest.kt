package com.hesier.lumy.fnos

import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException

class FnOsNextUpTest {
    @Test
    fun `completed history advances within a season and skips watched episodes`() = runBlocking {
        val first = episode("one", watched = true)
        val watched = episode("two", number = 2, watched = true)
        val next = episode("three", number = 3)
        val result = FnOsNextUp { id ->
            if (id == "show") listOf(season()) else listOf(next, first, watched)
        }.items(listOf(first))
        assertEquals(listOf("three"), result.map { it.id })
    }

    @Test
    fun `completed season skips empty seasons and specials to reach next regular season`() = runBlocking {
        val first = episode("one", watched = true)
        val next = episode("four", season = 4)
        val requests = mutableListOf<String>()
        val result = FnOsNextUp { id ->
            requests += id
            when (id) {
                "show" -> listOf(season(4), season(0), season(1), season(3))
                "season1" -> listOf(first)
                "season4" -> listOf(next)
                "season0" -> error("Specials must be skipped")
                else -> emptyList()
            }
        }.items(listOf(first))
        assertEquals(listOf("four"), result.map { it.id })
        assertEquals(listOf("show", "season1", "season3", "season4"), requests)
    }

    @Test
    fun `special history only advances within specials`() = runBlocking {
        val anchor = episode("special", season = 0, watched = true)
        val result = FnOsNextUp { id ->
            when (id) {
                "show" -> listOf(season(0), season(1))
                "season0" -> listOf(anchor)
                else -> error("Regular seasons must not be started from a special")
            }
        }.items(listOf(anchor))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `shows without any playback history are not next up`() = runBlocking {
        var calls = 0
        val nextUp = FnOsNextUp { calls++; emptyList() }
        assertTrue(nextUp.items(emptyList()).isEmpty())
        assertTrue(nextUp.items(listOf(episode("zero"))).isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun `in progress anchor recommends the following unwatched episode`() = runBlocking {
        val anchor = episode("active").copy(viewOffsetMs = 1000)
        val next = episode("next", number = 2)
        val result = FnOsNextUp { id ->
            if (id == "show") listOf(season()) else listOf(anchor, next)
        }.items(listOf(anchor))
        assertEquals(listOf(next), result)
        assertEquals(1000L, anchor.viewOffsetMs)
    }

    @Test
    fun `following episode already in progress remains only in continue watching`() = runBlocking {
        val anchor = episode("one", watched = true)
        val active = episode("active", number = 2).copy(viewOffsetMs = 1000)
        val next = episode("three", number = 3)
        val result = FnOsNextUp { id ->
            if (id == "show") listOf(season()) else listOf(anchor, active, next)
        }.items(listOf(anchor))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `missing season does not restart the show but removed anchor uses episode number`() = runBlocking {
        val anchor = episode("gone", number = 2, watched = true)
        val next = episode("three", number = 3)
        val nextUp = FnOsNextUp { id ->
            if (id == "show") listOf(season()) else listOf(episode("one"), next)
        }
        assertEquals(listOf("three"), nextUp.items(listOf(anchor)).map { it.id })
        assertTrue(nextUp.items(listOf(anchor.copy(parentId = "missing"))).isEmpty())
    }

    @Test
    fun `one result per show follows history order and isolates failures`() = runBlocking {
        val first = episode("first", show = "first", watched = true)
        val second = episode("second", show = "second", watched = true)
        val broken = episode("broken", show = "broken", watched = true)
        val result = FnOsNextUp { id ->
            when {
                id == "broken" -> throw IOException("Unavailable")
                id.endsWith("season1") -> {
                    val show = id.removeSuffix("season1")
                    listOf(episode(show, show = show, watched = true), episode("next-$show", number = 2, show = show))
                }
                else -> listOf(season(show = id))
            }
        }.items(listOf(broken, second, first, first), limit = 1)
        assertEquals(listOf("next-second"), result.map { it.id })
    }

    @Test
    fun `history scanning and request concurrency are bounded`() = runBlocking {
        var active = 0
        var maximum = 0
        var calls = 0
        val result = FnOsNextUp {
            calls++
            active++
            maximum = maxOf(maximum, active)
            delay(1)
            active--
            emptyList()
        }.items((1..100).map { episode("item$it", show = "show$it", watched = true) })
        assertTrue(result.isEmpty())
        assertEquals(40, calls)
        assertEquals(4, maximum)
    }

    @Test
    fun `cancellation propagates instead of returning partial recommendations`() = runBlocking {
        try {
            FnOsNextUp { throw CancellationException("Cancelled") }
                .items(listOf(episode("one", watched = true)))
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Cancellation is owned by the caller.
        }
    }

    @Test
    fun `raw history retains completed and zero position records while resume stays filtered`() = runBlocking {
        val dispatcher = FnOsTestDispatcher().apply {
            resume = """[
                {"guid":"done","media_guid":"done-file","ts":0,"watched":true},
                {"guid":"finished","media_guid":"finished-file","ts":50,"duration":50},
                {"guid":"zero","ts":0},
                {"guid":"active","media_guid":"active-file","ts":5,"duration":50}
            ]"""
        }
        val web = MockWebServer().apply { this.dispatcher = dispatcher }
        try {
            val session = ServerSession(ServerKind.FNOS, "server", "Test", web.url("/").toString().trimEnd('/'), "token")
            val client = FnOsClient(FnOsClientInfo("Test", "1", "device", "Box"))
            val progress = FnOsProgress(FnOsNativeClient(session, client))
            val history = progress.history()
            assertEquals(listOf("done", "finished", "zero", "active"), history.map { it.itemId })
            assertEquals(listOf(true, true, false, false), history.map { it.watched })
            assertEquals(listOf(FnOsResume("active", "active-file", 5000, 50000)), progress.resume(history))
            assertEquals(1, dispatcher.requests.count { it.path == "/v/api/v1/play/list" })
        } finally {
            web.shutdown()
        }
    }

    private fun episode(
        id: String,
        number: Int = 1,
        season: Int = 1,
        show: String = "show",
        watched: Boolean = false,
    ) = MediaItem(id, MediaType.EPISODE, id, parentId = "${show.prefix()}season$season",
        grandparentId = show, seasonNumber = season, episodeNumber = number, watched = watched)

    private fun season(number: Int = 1, show: String = "show") = MediaItem(
        "${show.prefix()}season$number", MediaType.SEASON, "Season $number", parentId = show, seasonNumber = number,
    )

    private fun String.prefix() = if (this == "show") "" else this
}

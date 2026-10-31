package com.hesier.lumy.fnos

import com.hesier.lumy.core.HubLabel
import com.hesier.lumy.core.LibraryQuery
import com.hesier.lumy.core.PlaybackMode
import com.hesier.lumy.core.PlaybackRequest
import com.hesier.lumy.core.PlaybackState
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class FnOsResumeTest {
    private val native = FnOsTestDispatcher()
    private val web = MockWebServer().apply { dispatcher = native }
    private val client = FnOsClient(FnOsClientInfo("Test Player", "1", "test-device", "Test Box"))
    private fun server() = FnOsMediaServer(
        ServerSession(ServerKind.FNOS, "test-server", "Test Media", web.url("/").toString().trimEnd('/'),
            "test-token", userId = "test-user"), client,
    )
    private fun enqueue(body: String) = web.enqueue(MockResponse().setBody(body))

    @After
    fun close() = web.shutdown()

    @Test
    fun `resume binds canonical records to the remembered file version in detail and home`() = runBlocking {
        native.resume = """[{"guid":"canonical-a","media_guid":"file-b","ts":32,"duration":120}]"""
        enqueue(FILE_B)
        val media = server()
        val detail = media.detail("file-b")
        assertEquals("file-b", detail.item.id)
        assertEquals(32_000L, detail.item.viewOffsetMs)
        assertEquals(120_000L, detail.item.durationMs)
        enqueue(FILE_B)
        enqueue("""{"Items":[]}""")
        val hubs = media.homeHubs()
        val resume = hubs.single { it.label == HubLabel.ContinueWatching }
        assertEquals(listOf("file-b"), resume.items.map { it.id })
        assertEquals(32_000L, resume.items.single().viewOffsetMs)
        val compat = drainCompat()
        assertEquals(2, compat.count { it.requestUrl!!.encodedPath == "/Items/file-b" })
        assertTrue(compat.none { it.requestUrl!!.encodedPath == "/Items/canonical-a" })
    }

    @Test
    fun `stopped native record appears in next detail refresh without stale resume cache`() = runBlocking {
        native.resume = """[{"guid":"canonical-a","media_guid":"file-b","ts":10,"duration":120}]"""
        native.info = """{"guid":"canonical-a","media_guid":"file-b","item":{"duration":120}}"""
        enqueue(FILE_B)
        val media = server()
        val before = media.detail("file-b")
        assertEquals(10_000L, before.item.viewOffsetMs)
        val request = PlaybackRequest(before.item, before.versions.single(), PlaybackMode.Direct, 10_000L, "test-play")
        media.reportProgress(request, PlaybackState.STOPPED, 45_000L, 120_000L)
        val record = native.requests.single { it.requestUrl!!.encodedPath.endsWith("/play/record") }
        assertTrue(record.body.clone().readUtf8().contains("\"media_guid\":\"file-b\""))
        native.resume = """[{"guid":"canonical-a","media_guid":"file-b","ts":45,"duration":120}]"""
        enqueue(FILE_B)
        assertEquals(45_000L, media.detail("file-b").item.viewOffsetMs)
        assertEquals(2, native.requests.count { it.requestUrl!!.encodedPath.endsWith("/play/list") })
    }

    @Test
    fun `catalog overlays native canonical watched state and file specific resume`() = runBlocking {
        native.resume = """[{"guid":"canonical-a","media_guid":"file-b","ts":25,"duration":120}]"""
        native.items = """{"list":[{"guid":"canonical-a","watched":1}],"total":1}"""
        val fileA = FILE_B.replace("file-b", "file-a")
        enqueue("""{"Items":[$fileA,$FILE_B],"TotalRecordCount":2}""")
        val result = server().libraryItems("movies", 0, 10, LibraryQuery())
        assertEquals(listOf(true), result.items.map { it.watched })
        assertEquals(listOf<Long?>(null), result.items.map { it.viewOffsetMs })
        val catalog = native.requests.single { it.requestUrl!!.encodedPath.endsWith("/item/list") }
        assertTrue(catalog.body.readUtf8().contains("\"ancestor_guid\":\"movies\""))
    }

    @Test
    fun `unwatch a series sends one native DELETE with its canonical series ID`() = runBlocking {
        enqueue("""{"Id":"series-a","Type":"Series"}""")
        server().setWatched("series-a", false)
        val mark = native.requests.single { it.requestUrl!!.encodedPath.endsWith("/item/watched") }
        assertEquals("DELETE", mark.method)
        assertEquals("""{"item_guid":"series-a"}""", mark.body.readUtf8())
    }

    private fun drainCompat(): List<RecordedRequest> = buildList {
        repeat(web.requestCount) {
            val request = web.takeRequest()
            if (!request.requestUrl!!.encodedPath.startsWith("/v/")) add(request)
        }
    }

    companion object {
        private const val FILE_B = """{"Id":"file-b","Name":"Test Film","Type":"Movie",
            "RunTimeTicks":1000000000,"MediaSources":[{"Id":"canonical-a","RunTimeTicks":1200000000}]}"""
    }
}

internal class FnOsTestDispatcher : QueueDispatcher() {
    val compatCount = AtomicInteger()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile var resume = "[]"
    @Volatile var items = """{"list":[],"total":0}"""
    @Volatile var info = "{}"
    @Volatile var search = "[]"
    @Volatile var tags = """{"genres":[]}"""
    @Volatile var genres = "[]"
    @Volatile var streams = "{}"

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath
        if (!path.startsWith("/v/")) {
            compatCount.incrementAndGet()
            return super.dispatch(request)
        }
        requests.add(request)
        return when (path) {
            "/v/" -> MockResponse().setBody("""<script src="/v/sign.js"></script>""")
            "/v/sign.js" -> MockResponse().setBody(FIXTURE)
            "/v/api/v1/play/list" -> data(resume)
            "/v/api/v1/item/list" -> data(items)
            "/v/api/v1/search/list" -> data(search)
            "/v/api/v1/tag/list" -> data(tags)
            "/v/api/v1/tag/genres" -> data(genres)
            "/v/api/v1/play/info" -> data(info)
            "/v/api/v1/stream" -> data(streams)
            "/v/api/v1/item/watched", "/v/api/v1/play/record" -> data("true")
            else -> if (path.startsWith("/v/api/v1/item/")) data("""{"is_watched":0}""")
            else MockResponse().setResponseCode(404)
        }
    }

    private fun data(value: String) = MockResponse().setBody("""{"code":0,"data":$value}""")

    companion object {
        private val FIXTURE = """
            var demo=(()=>{let e=new Uint8Array([103,104,121,117,116,115,100,44,106,100,120]),t=new Uint8Array(11);for(let n=0;n<11;n++)t[n]=e[n]^1;return new TextDecoder(`utf-8`).decode(t)})();
            var sign=(r,s,c,o,t)=>{let l=[`fixture-app`,r,s,c,o,t].join(`_`);return `nonce=signature`;};
            client({apiKey:demo,header:`authx`});
        """.trimIndent()
    }
}

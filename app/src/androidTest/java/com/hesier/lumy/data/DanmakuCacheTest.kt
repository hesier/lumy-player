package com.hesier.lumy.data

import android.content.Context
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.ServerDanmaku
import com.hesier.lumy.core.ServerDanmakuFormat
import com.hesier.lumy.net.danmaku.DanmakuComment
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

/** Every request is intercepted before DNS; all files belong to this unique fixture. */
internal class DanmakuCacheTest(private val context: Context) {
    private val name = "danmaku-cache-test-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val settings = DanmakuSourceSettings(preferences)
    private val directory = File(context.cacheDir, name)
    private var store = DanmakuStore(context, name)
    private val requests = mutableListOf<String>()
    private var offline = false
    private var commentText = "online"
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val path = request.url.encodedPath
        requests += path
        if (offline) throw IOException("Fixture offline")
        val body = when {
            path == "/plugin" -> XML
            path.endsWith("/match") -> "{}"
            "/search/anime" in path -> SEARCH
            "/bangumi/" in path -> DETAILS
            "/comment/" in path -> COMMENTS.replace("online", commentText)
            else -> error("Unexpected fixture path: $path")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
            .message("Fixture").body(body.toResponseBody()).build()
    }.build()
    private val services = DanmakuServices(context, settings)
    private var repository = DanmakuRepository(services, store, http)
    private val media = DanmakuMedia("server-a:item", "server-a", "Chosen", null,
        MediaType.EPISODE, 2023, 1, 1, null, "version-a")

    fun runAll(): String = runBlocking {
        settings.customUrl = "https://danmaku-fixture.invalid/source-a"
        settings.onlineSource = DanmakuOnlineSource.CUSTOM
        try {
            permanentAutomaticCache()
            manualAndPriority()
            invalidationAndIsolation()
            manualMetadataRefresh()
            capacityAndDanglingReference()
            failedWritesKeepLoadedComments()
            "Permanent offline cache, write-failure recovery, manual offsets, priority, isolation and 64 MiB eviction passed"
        } finally {
            store.clearCache(); store.clearMatches()
            preferences.edit().clear().commit()
            context.getSharedPreferences("${name}_matches", Context.MODE_PRIVATE).edit().clear().commit()
            check(directory.name == name && name.startsWith("danmaku-cache-test-"))
            directory.deleteRecursively()
        }
    }

    private suspend fun permanentAutomaticCache() {
        val first = loaded(media)
        check(first.automatic && first.comments.single().text == "online")
        check(requests.any { "/search/anime" in it } && requests.any { "/comment/" in it })
        check(store.match(media.key, first.source.identity) == null && store.choice(media.key) == null)
        directory.listFiles().orEmpty().forEach { check(it.setLastModified(1L)) }
        reopen(); requests.clear(); offline = true
        val replay = loaded(media)
        check(replay.automatic && replay.comments == first.comments)
        check(replay.match?.episode == first.match?.episode && replay.match?.anime?.id == first.match?.anime?.id)
        check(replay.sourceOffsetMs == first.sourceOffsetMs && replay.match?.anime?.title == first.match?.anime?.title)
        check(requests.isEmpty()) { "Old automatic cache contacted network: $requests" }
        check(store.match(media.key, first.source.identity) == null && store.choice(media.key) == null)
        offline = false; requests.clear()
        check(loaded(media, force = true).automatic)
        check(requests.any { "/search/anime" in it } && requests.any { "/comment/" in it })
    }

    private suspend fun manualAndPriority() {
        val automatic = loaded(media)
        val pluginMedia = media.copy(plugin = ServerDanmaku("https://danmaku-fixture.invalid/plugin",
            emptyMap(), ServerDanmakuFormat.XML))
        requests.clear()
        val plugin = loaded(pluginMedia)
        check(plugin.source.kind == DanmakuSourceKind.SERVER && plugin.comments.single().text == "plugin")
        check(listOf("/plugin") == requests)
        val match = checkNotNull(automatic.match).copy(sourceOffsetMs = -1750L)
        store.remember(media.key, match)
        val saved = checkNotNull(store.match(media.key, match.choice.identity))
        directory.listFiles().orEmpty().forEach { check(it.setLastModified(1L)) }
        reopen(); requests.clear(); offline = true
        val manual = loaded(pluginMedia)
        check(!manual.automatic && manual.sourceOffsetMs == -1750L && manual.match == saved)
        check(manual.source == match.choice && requests.isEmpty())
        offline = false; requests.clear()
        val forced = loaded(pluginMedia, force = true)
        check(!forced.automatic && forced.sourceOffsetMs == -1750L)
        check(requests.any { "/bangumi/" in it } && requests.any { "/comment/" in it })
        check(requests.none { it == "/plugin" })
        store.clearCache(); requests.clear()
        check(store.match(media.key, match.choice.identity) == saved)
        check(!loaded(media).automatic)
        check(requests.any { "/bangumi/" in it } && requests.any { "/comment/" in it })
    }

    private suspend fun invalidationAndIsolation() {
        store.restoreAuto(media.key); requests.clear()
        check(loaded(media).automatic && requests.any { "/search/anime" in it })
        val source = services.endpoint.identity
        val changed = listOf(media.copy(key = "server-a:other"),
            media.copy(key = "server-b:item", serverKey = "server-b"), media.copy(versionId = "version-b"),
            media.copy(title = "Other"), media.copy(originalTitle = "Original"),
            media.copy(type = MediaType.MOVIE), media.copy(year = 2024),
            media.copy(season = 2), media.copy(number = 2))
        changed.forEach { check(store.cachedAutomatic(it, source) == null) }
        check(store.cachedAutomatic(media, "official:dandanplay") == null)
        offline = true
        for (other in changed.take(3)) {
            requests.clear()
            check(repository.load(other) is DanmakuLoad.Failed && requests.isNotEmpty())
        }
        settings.customUrl = "https://danmaku-fixture.invalid/source-b"; requests.clear()
        check(repository.load(media) is DanmakuLoad.Failed && requests.isNotEmpty())
        settings.customUrl = "https://danmaku-fixture.invalid/source-a"; requests.clear()
        check(loaded(media).automatic && requests.isEmpty())
        offline = false
        store.clearMatches(); requests.clear()
        check(store.cachedAutomatic(media, source) == null)
        check(loaded(media).automatic && requests.any { "/search/anime" in it })
        store.clearCache(); requests.clear()
        check(loaded(media).automatic)
        check(requests.any { "/search/anime" in it } && requests.any { "/comment/" in it })
    }

    private suspend fun manualMetadataRefresh() {
        store.clearCache(); store.clearMatches()
        val match = checkNotNull(loaded(media).match)
        val legacy = match.copy(anime = match.anime.copy(year = null))
        store.cache(store.contentKey(legacy), listOf(DanmakuComment(1, 1000, "old", 1, 0xffffff)))
        store.remember(media.key, legacy)
        commentText = "fresh"
        check(loaded(media, force = true).comments.single().text == "fresh")
        reopen(); requests.clear(); offline = true
        val replay = loaded(media)
        check(replay.comments.single().text == "fresh" && replay.match?.anime?.year == 2023)
        check(requests.isEmpty()) { "Refreshed metadata was not associated with the new content" }
        offline = false; commentText = "online"
    }

    private suspend fun capacityAndDanglingReference() {
        store.clearCache(); store.clearMatches()
        val loaded = loaded(media)
        val match = checkNotNull(loaded.match)
        val oldKey = store.contentKey(match)
        val oldFile = File(directory, "${danmakuHash(oldKey)}.bin")
        check(oldFile.setLastModified(1_000L))
        directory.listFiles().orEmpty().filter { it.extension == "ref" }.forEach {
            check(it.setLastModified(2_000L))
        }
        val boundary = listOf(DanmakuComment(2, 2000, "boundary", 1, 0xffffff))
        store.cache("boundary", boundary)
        val missing = MAX_BYTES - directory.listFiles().orEmpty().sumOf { it.length() }
        check(missing > 0)
        // Sparse padding produces exact logical capacity without allocating 64 MiB on disk.
        val padding = File(directory, "fixture-padding.bin")
        RandomAccessFile(padding, "rw").use { it.setLength(missing) }
        check(padding.setLastModified(10_000L))
        store.cache("boundary", boundary)
        check(MAX_BYTES == directory.listFiles().orEmpty().sumOf { it.length() })
        check(store.cached(oldKey) == loaded.comments && store.cachedAutomatic(media, match.choice.identity) != null)
        store.cache("newest", listOf(DanmakuComment(3, 3000, "overflow", 1, 0xffffff)))
        check(!oldFile.exists() && store.cached(oldKey) == null)
        check(store.cachedAutomatic(media, match.choice.identity) == null)
        check(directory.listFiles().orEmpty().sumOf { it.length() } <= MAX_BYTES)
        check(store.cached("newest")?.single()?.text == "overflow")
        requests.clear(); offline = true
        check(repository.load(media) is DanmakuLoad.Failed && requests.isNotEmpty())
        offline = false
    }

    private suspend fun failedWritesKeepLoadedComments() {
        store.clearCache(); store.clearMatches()
        check(directory.delete())
        directory.writeText("Fixture blocks cache directory creation")
        try {
            check(loaded(media).comments.single().text == "online")
            val plugin = media.copy(plugin = ServerDanmaku("https://danmaku-fixture.invalid/plugin",
                emptyMap(), ServerDanmakuFormat.XML))
            requests.clear()
            check(loaded(plugin).comments.single().text == "plugin")
            check(listOf("/plugin") == requests)
        } finally { check(directory.delete()) }
        val first = loaded(media)
        val match = checkNotNull(first.match)
        store.clearMatches()
        check(directory.setWritable(false, false) && !directory.canWrite())
        try {
            requests.clear()
            check(loaded(media).comments == first.comments)
            check(requests.any { "/search/anime" in it } && requests.none { "/comment/" in it })
            check(store.cachedAutomatic(media, match.choice.identity) == null)
        } finally { check(directory.setWritable(true, false)) }
        val key = store.contentKey(match)
        // A serialization I/O failure after opening the temp file must preserve the old entry.
        store.cache(key, listOf(DanmakuComment(1, 0, "x".repeat(70_000), 1, 0xffffff)))
        check(first.comments == store.cached(key))
        check(directory.listFiles().orEmpty().none { it.extension == "part" })
        check(loaded(media).comments == first.comments)
        check(store.cachedAutomatic(media, match.choice.identity) != null)
    }

    private fun reopen() {
        store = DanmakuStore(context, name)
        repository = DanmakuRepository(services, store, http)
    }

    private suspend fun loaded(item: DanmakuMedia, force: Boolean = false): DanmakuLoad.Loaded {
        val result = repository.load(item, force)
        check(result is DanmakuLoad.Loaded) { "Expected loaded fixture, got $result; requests=$requests" }
        return result
    }

    private companion object {
        const val MAX_BYTES = 64 * 1024 * 1024L
        const val XML = "<i><d p=\"1.0,1,25,16777215,0,0,user,1\">plugin</d></i>"
        const val SEARCH = "{\"success\":true,\"animes\":[{\"animeId\":1,\"animeTitle\":\"Chosen\",\"year\":2023}]}"
        const val DETAILS = "{\"success\":true,\"bangumi\":{\"animeId\":1,\"animeTitle\":\"Chosen\",\"year\":2023,\"episodes\":[{\"episodeId\":11,\"episodeTitle\":\"First\",\"episodeNumber\":\"1\"}]}}"
        const val COMMENTS = "{\"comments\":[{\"cid\":1,\"p\":\"1,1,16777215,user\",\"m\":\"online\"}]}"
    }
}

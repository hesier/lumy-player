package com.hesier.lumy.data

import android.content.Context
import com.hesier.lumy.net.danmaku.DanmakuAnime
import com.hesier.lumy.net.danmaku.DanmakuComment
import com.hesier.lumy.net.danmaku.DanmakuEpisode
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Raw normalized comments are cached separately from explicit per-item corrections. */
class DanmakuStore(context: Context, name: String = "danmaku") {
    private val prefs = context.getSharedPreferences("${name}_matches", Context.MODE_PRIVATE)
    private val directory = File(context.cacheDir, name)

    internal fun choice(media: String): DanmakuChoice? = runCatching {
        decode(prefs.getString("choice:$media", null))?.let {
            DanmakuChoice(DanmakuSourceKind.valueOf(it.getString("kind")), it.getString("source"))
        }
    }.getOrNull()

    internal fun choose(media: String, value: DanmakuChoice) {
        prefs.edit().putString("choice:$media", JSONObject().put("kind", value.kind.name)
            .put("source", value.identity).toString()).apply()
    }

    internal fun match(media: String, source: String): DanmakuMatch? = runCatching {
        val data = decode(prefs.getString("match:$media:$source", null)) ?: return null
        DanmakuMatch(DanmakuChoice(DanmakuSourceKind.valueOf(data.getString("kind")), source),
            DanmakuAnime(data.getString("animeId"), data.getString("animeTitle"), data.optString("animeType").ifEmpty { null }, data.optionalInt("animeYear")),
            DanmakuEpisode(data.getString("episodeId"), data.getString("episodeTitle"), data.optString("episodeNumber").ifEmpty { null }, data.optString("episodeSeason").ifEmpty { null }),
            data.optionalInt("year"), data.optionalInt("season"), data.optionalInt("number"), data.optLong("sourceOffsetMs", 0))
    }.getOrNull()

    internal fun remember(media: String, value: DanmakuMatch) {
        val data = JSONObject().put("kind", value.choice.kind.name).put("animeId", value.anime.id)
            .put("animeTitle", value.anime.title).put("animeYear", value.anime.year).put("animeType", value.anime.type)
            .put("episodeId", value.episode.id)
            .put("episodeTitle", value.episode.title).put("episodeNumber", value.episode.number).put("episodeSeason", value.episode.seasonId)
            .put("year", value.year).put("season", value.season).put("number", value.number)
            .put("sourceOffsetMs", value.sourceOffsetMs)
        prefs.edit().putString("match:$media:${value.choice.identity}", data.toString()).apply()
        choose(media, value.choice)
    }

    internal fun restoreAuto(media: String) {
        val edit = prefs.edit().remove("choice:$media")
        prefs.all.keys.filter { it.startsWith("match:$media:") }.forEach(edit::remove)
        edit.apply()
    }

    fun clearMatches() { prefs.edit().clear().apply() }

    internal fun cached(key: String): List<DanmakuComment>? = synchronized(cacheLock) {
        val file = cacheFile(key)
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > CACHE_TTL_MS) return null
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                check(input.readInt() == CACHE_VERSION)
                val count = input.readInt()
                check(count in 0..100_000 && file.length() <= MAX_FILE_BYTES)
                List(count) { DanmakuComment(input.readLong(), input.readLong(), input.readUTF(), input.readInt(), input.readInt()) }
            }
        }.getOrNull()
    }

    internal fun cache(key: String, comments: List<DanmakuComment>) = synchronized(cacheLock) {
        directory.mkdirs()
        val file = cacheFile(key)
        val partial = File.createTempFile("download-", ".part", directory)
        try {
            DataOutputStream(partial.outputStream().buffered()).use { output ->
                output.writeInt(CACHE_VERSION); output.writeInt(comments.size)
                comments.forEach { item ->
                    output.writeLong(item.id); output.writeLong(item.timeMs); output.writeUTF(item.text)
                    output.writeInt(item.mode); output.writeInt(item.color)
                }
            }
            if (partial.length() <= MAX_FILE_BYTES && partial.renameTo(file)) trim()
        } finally { partial.delete() }
    }

    fun clearCache() = synchronized(cacheLock) { directory.listFiles()?.forEach { it.delete() }; Unit }
    internal fun invalidate(key: String) = synchronized(cacheLock) { cacheFile(key).delete(); Unit }

    private fun cacheFile(key: String) = File(directory, "${danmakuHash(key)}.bin")
    private fun trim() {
        val files = directory.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        var total = 0L
        files.forEach { total += it.length(); if (total > MAX_CACHE_BYTES) it.delete() }
    }
    private fun decode(value: String?) = value?.let { runCatching { JSONObject(it) }.getOrNull() }
    private fun JSONObject.optionalInt(key: String) = if (has(key) && !isNull(key)) getInt(key) else null

    private companion object {
        val cacheLock = Any()
        const val CACHE_VERSION = 1
        const val CACHE_TTL_MS = 6 * 60 * 60 * 1_000L
        const val MAX_FILE_BYTES = 16 * 1024 * 1024L
        const val MAX_CACHE_BYTES = 64 * 1024 * 1024L
    }
}

package com.hesier.lumy.net.danmaku

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.roundToLong

object DanmakuParser {
    fun parse(bytes: ByteArray, format: DanmakuFormat): List<DanmakuComment> {
        if (bytes.size > MAX_DANMAKU_BYTES) throw DanmakuException(DanmakuFailure.TOO_LARGE)
        try {
            val records = CommentCollector()
            when (format) {
                DanmakuFormat.XML -> parseDanmakuXml(bytes, records)
                DanmakuFormat.JSON -> parseJson(bytes, records, emby = false)
                DanmakuFormat.EMBY_JSON -> parseJson(bytes, records, emby = true)
            }
            return records.result()
        } catch (error: DanmakuException) { throw error }
        catch (error: Exception) { throw DanmakuException(DanmakuFailure.INVALID_RESPONSE, error) }
    }

    private fun parseJson(bytes: ByteArray, records: CommentCollector, emby: Boolean) {
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
        (root as? JsonObject)?.checkBusinessResult()
        if (!emby) {
            val rows = root as? JsonArray ?: (root as? JsonObject)?.field("comments") as? JsonArray
                ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE)
            rows.forEach { records.json(it, bili = false) }
            return
        }
        val data = (root as? JsonObject)?.field("data")
            ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE)
        if (data == JsonNull) return
        val groups = data as? JsonArray ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE)
        groups.forEach { group ->
            val events = (group as? JsonObject)?.field("danmuEvents")
                ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE)
            if (events == JsonNull) return@forEach
            (events as? JsonArray ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE))
                .forEach { records.json(it, bili = true) }
        }
    }
}

internal class CommentCollector {
    private var count = 0
    private val comments = LinkedHashMap<Key, DanmakuComment>()

    fun json(element: JsonElement, bili: Boolean) {
        val row = element as? JsonObject
        add(row?.field("p")?.text(), row?.field("m")?.text(), row?.field("cid")?.text(), bili)
    }

    fun add(properties: String?, text: String?, remoteId: String? = null, bili: Boolean = true) {
        if (++count > MAX_DANMAKU_COMMENTS) throw DanmakuException(DanmakuFailure.TOO_LARGE)
        val fields = properties?.split(',') ?: return
        if (fields.size < (if (bili) 8 else 4) || text.isNullOrBlank()) return
        val seconds = fields[0].toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..86_400.0 } ?: return
        val mode = fields[1].toIntOrNull()?.takeIf { it == 1 || it == 4 || it == 5 } ?: return
        val color = fields[if (bili) 3 else 2].toLongOrNull()?.and(0xFFFFFF)?.toInt() ?: return
        val timeMs = (seconds * 1_000).roundToLong()
        val end = text.offsetByCodePoints(0, text.codePointCount(0, text.length).coerceAtMost(80))
        val content = text.substring(0, end)
        val key = Key(timeMs, content, mode, color)
        val id = remoteId ?: if (bili) fields[7] else null
        if (key !in comments) comments[key] = DanmakuComment(stableId(id, key), timeMs, content, mode, color)
    }

    fun result(): List<DanmakuComment> {
        if (count > 0 && comments.isEmpty()) throw DanmakuException(DanmakuFailure.INVALID_RESPONSE)
        return comments.values.sortedBy { it.timeMs }
    }

    private fun stableId(id: String?, key: Key): Long = ByteBuffer.wrap(
        MessageDigest.getInstance("SHA-256").digest((key.toString() + "\u0000" + id.orEmpty()).toByteArray(Charsets.UTF_8)),
    ).long

    private data class Key(val timeMs: Long, val text: String, val mode: Int, val color: Int)
}

internal fun JsonObject.field(name: String): JsonElement? = this[name] ?: entries.firstOrNull {
    it.key.equals(name, ignoreCase = true)
}?.value
internal fun JsonElement.text(): String? = (this as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.checkBusinessResult() {
    val success = field("success") ?: return
    if ((success as? JsonPrimitive)?.booleanOrNull != true) throw DanmakuException(DanmakuFailure.UNAVAILABLE)
}

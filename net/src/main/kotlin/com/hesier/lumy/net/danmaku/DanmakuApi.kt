package com.hesier.lumy.net.danmaku

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import java.security.MessageDigest
import java.math.RoundingMode

class DanmakuApi(http: OkHttpClient, val endpoint: DanmakuEndpoint) {
    private val transport = DanmakuTransport(http)

    suspend fun search(keyword: String): List<DanmakuAnime> = withContext(Dispatchers.IO) {
        val root = request("search", "anime", query = mapOf("keyword" to keyword))
        val rows = root.field("animes") as? JsonArray ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE)
        val result = rows.mapNotNull { (it as? JsonObject)?.anime() }
        if (rows.isNotEmpty() && result.isEmpty()) throw DanmakuException(DanmakuFailure.INVALID_RESPONSE)
        result
    }

    /** Sends a synthetic title only; matching remains an optional server capability. */
    suspend fun match(fileName: String): DanmakuMatchResponse? = withContext(Dispatchers.IO) {
        val url = url("match", query = emptyMap())
        val body = buildJsonObject {
            put("fileName", fileName)
            if (endpoint.official) {
                put("matchMode", "fileNameOnly")
                // The official API validates hash format even in filename-only mode.
                put("fileHash", FILENAME_ONLY_HASH_PLACEHOLDER)
            }
        }.toString()
        val bytes = transport.postJson(url.toString(), body, headers(url)) ?: return@withContext null
        val root = parseResponse(bytes)
        if (endpoint.official && (root.field("success") as? JsonPrimitive)?.booleanOrNull == false &&
            (root.field("errorCode") as? JsonPrimitive)?.intOrNull == MATCH_INVALID_PARAMETERS)
            return@withContext null
        root.checkBusinessResult()
        val matched = root.field("isMatched")?.let { flag ->
            (flag as? JsonPrimitive)?.booleanOrNull ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE)
        }
        val candidates = root.field("matches")
        if ((candidates == null || candidates == JsonNull) && matched != true) return@withContext null
        val matches = candidates as? JsonArray ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE)
        if (matches.isEmpty()) return@withContext null
        val parsed = matches.asSequence().map { element ->
            val row = element as? JsonObject ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE)
            DanmakuMatchCandidate(row.field("animeId")?.remoteId()
                ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE), row.field("episodeId")?.remoteId()
                ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE), row.shiftMillis())
        }.distinct().take(MAX_MATCH_CANDIDATES + 1).toList()
        DanmakuMatchResponse(matched, parsed.take(MAX_MATCH_CANDIDATES), parsed.size > MAX_MATCH_CANDIDATES)
    }

    suspend fun details(animeId: String): DanmakuAnime = withContext(Dispatchers.IO) {
        val root = request("bangumi", animeId)
        (root.field("bangumi") as? JsonObject ?: root.field("anime") as? JsonObject)?.anime()
            ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE)
    }

    suspend fun comments(episodeId: String): List<DanmakuComment> = withContext(Dispatchers.IO) {
        val query = if (endpoint.official) mapOf("withRelated" to "true") else mapOf("format" to "json")
        val url = url("comment", episodeId, query = query)
        DanmakuParser.parse(transport.get(url.toString(), headers(url)), DanmakuFormat.JSON)
    }

    private suspend fun request(vararg path: String, query: Map<String, String> = emptyMap()): JsonObject {
        val url = url(*path, query = query)
        return parseResponse(transport.get(url.toString(), headers(url))).also { it.checkBusinessResult() }
    }

    private fun parseResponse(bytes: ByteArray): JsonObject {
        try {
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
                ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE)
            return root
        } catch (error: DanmakuException) { throw error }
        catch (error: Exception) { throw DanmakuException(DanmakuFailure.INVALID_RESPONSE, error) }
    }

    private fun url(vararg path: String, query: Map<String, String>): HttpUrl = endpoint.url.newBuilder().apply {
        if (!endpoint.url.encodedPath.trimEnd('/').endsWith("/api/v2")) addPathSegments("api/v2")
        path.forEach { addPathSegment(it) }
        query.forEach { (key, value) -> addQueryParameter(key, value) }
    }.build()

    private fun headers(url: HttpUrl): Map<String, String> {
        if (!endpoint.official) return mapOf("Accept" to "application/json")
        val auth = endpoint.credentials?.takeIf { it.appId.isNotBlank() && it.appSecret.isNotBlank() }
            ?: throw DanmakuException(DanmakuFailure.UNAVAILABLE)
        val timestamp = (System.currentTimeMillis() / 1_000).toString()
        val signature = MessageDigest.getInstance("SHA-256")
            .digest((auth.appId + timestamp + url.encodedPath + auth.appSecret).toByteArray(Charsets.UTF_8))
            .toByteString().base64()
        return mapOf("Accept" to "application/json", "X-AppId" to auth.appId,
            "X-Timestamp" to timestamp, "X-Signature" to signature)
    }
}

private const val MAX_MATCH_CANDIDATES = 6
private const val MATCH_INVALID_PARAMETERS = 2
private const val FILENAME_ONLY_HASH_PLACEHOLDER = "00000000000000000000000000000000"

private fun JsonObject.shiftMillis(): Long {
    val value = field("shift") ?: return 0
    if (value == JsonNull) return 0
    val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()
        ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE)
    return try { number.movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact() }
    catch (error: ArithmeticException) { throw DanmakuException(DanmakuFailure.INVALID_RESPONSE, error) }
}

private fun JsonObject.anime(): DanmakuAnime? {
    val id = field("bangumiId")?.remoteId() ?: field("animeId")?.remoteId() ?: return null
    val title = field("animeTitle")?.text()?.takeIf { it.isNotBlank() } ?: return null
    val episodes = (field("episodes") as? JsonArray).orEmpty().mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val episodeId = row.field("episodeId")?.remoteId() ?: return@mapNotNull null
        DanmakuEpisode(episodeId, row.field("episodeTitle")?.text().orEmpty(),
            row.field("episodeNumber")?.text(), row.field("seasonId")?.remoteId())
    }
    val year = (field("year") as? JsonPrimitive)?.intOrNull
        ?: field("startDate")?.text()?.take(4)?.toIntOrNull()
        ?: TITLE_YEAR.find(title)?.let { (it.groups[1] ?: it.groups[2])?.value?.toIntOrNull() }
    return DanmakuAnime(id, title, field("typeDescription")?.text() ?: field("type")?.text(), year, episodes)
}

private val TITLE_YEAR = Regex("\\(((?:19|20)[0-9]{2})\\)|（((?:19|20)[0-9]{2})）")

private fun JsonElement.remoteId(): String? = (this as? JsonPrimitive)?.let {
    if (it.isString || it.longOrNull != null) it.text()?.takeIf(String::isNotBlank) else null
}

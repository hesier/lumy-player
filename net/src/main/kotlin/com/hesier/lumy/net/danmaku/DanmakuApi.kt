package com.hesier.lumy.net.danmaku

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
    suspend fun match(fileName: String): DanmakuMatchCandidate? = withContext(Dispatchers.IO) {
        val url = url("match", query = emptyMap())
        val body = buildJsonObject {
            put("fileName", fileName)
            if (endpoint.official) put("matchMode", "fileNameOnly")
        }.toString()
        val bytes = transport.postJson(url.toString(), body, headers(url)) ?: return@withContext null
        val root = parseResponse(bytes)
        root.field("isMatched")?.let { flag ->
            if ((flag as? JsonPrimitive)?.booleanOrNull == null)
                throw DanmakuException(DanmakuFailure.INVALID_RESPONSE)
        }
        val matches = root.field("matches") as? JsonArray ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE)
        if (matches.isEmpty()) return@withContext null
        val first = matches.first() as? JsonObject ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE)
        DanmakuMatchCandidate(first.field("animeId")?.remoteId()
            ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE), first.field("episodeId")?.remoteId()
            ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE))
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
        return parseResponse(transport.get(url.toString(), headers(url)))
    }

    private fun parseResponse(bytes: ByteArray): JsonObject {
        try {
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
                ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE)
            root.checkBusinessResult()
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

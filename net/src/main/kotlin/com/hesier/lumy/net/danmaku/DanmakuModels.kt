package com.hesier.lumy.net.danmaku

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.security.MessageDigest

data class DanmakuComment(val id: Long, val timeMs: Long, val text: String, val mode: Int, val color: Int)
data class DanmakuMatchCandidate(val animeId: String, val episodeId: String, val shiftMs: Long = 0)
data class DanmakuMatchResponse(val isMatched: Boolean?, val candidates: List<DanmakuMatchCandidate>,
    val truncated: Boolean = false)
data class DanmakuEpisode(val id: String, val title: String, val number: String? = null, val seasonId: String? = null)
data class DanmakuAnime(val id: String, val title: String, val type: String? = null,
    val year: Int? = null, val episodes: List<DanmakuEpisode> = emptyList())
data class DanmakuCredentials(val appId: String, val appSecret: String) {
    override fun toString() = "DanmakuCredentials(redacted)"
}

data class DanmakuEndpoint(val baseUrl: String, val official: Boolean = false,
    val credentials: DanmakuCredentials? = null) {
    internal val url = baseUrl.trim().toHttpUrlOrNull()?.also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null)
        require(!baseUrl.substringAfter("://").substringBefore('/').contains('@'))
        require(!official || it.toString().trimEnd('/') == OFFICIAL_URL)
    }?.let { it.newBuilder().encodedPath(it.encodedPath.trimEnd('/') + "/").build() }
        ?: throw IllegalArgumentException("Invalid danmaku endpoint")
    val identity: String = if (official) "official:dandanplay" else "custom:" + sha256(url.toString())

    override fun toString() = "DanmakuEndpoint(identity=$identity)"

    companion object { const val OFFICIAL_URL = "https://api.dandanplay.net" }
}

enum class DanmakuFormat { JSON, XML, EMBY_JSON }
enum class DanmakuFailure { AUTH, CONNECTION, INVALID_RESPONSE, NOT_FOUND, UNAVAILABLE, TOO_LARGE, RATE_LIMITED }

/** 原始网络异常可能携带路径令牌；异常链只保留异常类型。 */
class DanmakuException(val reason: DanmakuFailure, cause: Throwable? = null) :
    IOException(reason.name, cause?.let { IOException(it.javaClass.simpleName) })

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal const val MAX_DANMAKU_BYTES = 8 * 1024 * 1024
internal const val MAX_DANMAKU_COMMENTS = 100_000

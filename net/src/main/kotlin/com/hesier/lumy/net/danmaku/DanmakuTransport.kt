package com.hesier.lumy.net.danmaku

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.Locale
import kotlin.coroutines.resumeWithException

/** 重定向逐跳处理；取消处理一直保持到响应体读完。 */
class DanmakuTransport(private val http: OkHttpClient) {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .retryOnConnectionFailure(false).build()

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val normalized = checkedUrl(url).toString()
        // 与实际 HTTP 头语义一致：同名头不区分大小写，最后一次赋值生效。
        val copiedHeaders = headers.entries.associate { it.key.lowercase(Locale.ROOT) to it.value }
        return DanmakuRequestPool.get(http, normalized, copiedHeaders) { getUnshared(normalized, copiedHeaders) }
    }

    private suspend fun getUnshared(url: String, headers: Map<String, String>): ByteArray {
        var current = checkedUrl(url)
        var currentHeaders = headers
        repeat(MAX_REDIRECTS + 1) { hop ->
            val request = try {
                Request.Builder().url(current).apply { currentHeaders.forEach { (key, value) -> header(key, value) } }.build()
            } catch (error: IllegalArgumentException) { throw DanmakuException(DanmakuFailure.INVALID_RESPONSE, error) }
            val result = client.newCall(request).read()
            if (result.location == null) return result.bytes
            if (hop == MAX_REDIRECTS) throw DanmakuException(DanmakuFailure.UNAVAILABLE)
            val next = current.resolve(result.location)?.let { checkedUrl(it.toString()) }
                ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE)
            if (!sameOrigin(current, next)) currentHeaders = currentHeaders.filterKeys {
                it.equals("Accept", true) || it.equals("User-Agent", true)
            }
            current = next
        }
        throw DanmakuException(DanmakuFailure.UNAVAILABLE)
    }

    private suspend fun Call.read(): Reply = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(DanmakuException(DanmakuFailure.CONNECTION, e))
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching {
                    response.use {
                        continuation.context.ensureActive()
                        if (it.code in REDIRECT_CODES) return@use Reply(location = it.header("Location")
                            ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE))
                        checkStatus(it.code)
                        Reply(bytes = readLimited(it))
                    }
                }.recoverCatching { error ->
                    if (error is DanmakuException || error is java.util.concurrent.CancellationException) throw error
                    throw DanmakuException(DanmakuFailure.CONNECTION, error)
                })
            }
        })
    }

    private fun readLimited(response: Response): ByteArray {
        val body = response.body ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE)
        if (body.contentLength() > MAX_DANMAKU_BYTES) throw DanmakuException(DanmakuFailure.TOO_LARGE)
        val output = ByteArrayOutputStream()
        val source = body.byteStream()
        val decoded = if (response.header("Content-Encoding").equals("gzip", true)) GZIPInputStream(source) else source
        decoded.use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_DANMAKU_BYTES) throw DanmakuException(DanmakuFailure.TOO_LARGE)
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun checkedUrl(value: String): HttpUrl = value.toHttpUrlOrNull()?.takeIf {
        it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
    } ?: throw DanmakuException(DanmakuFailure.INVALID_RESPONSE)

    private fun checkStatus(code: Int) {
        val failure = when (code) {
            in 200..299 -> return
            401, 403 -> DanmakuFailure.AUTH
            404 -> DanmakuFailure.NOT_FOUND
            429 -> DanmakuFailure.RATE_LIMITED
            else -> DanmakuFailure.UNAVAILABLE
        }
        throw DanmakuException(failure)
    }

    private data class Reply(val bytes: ByteArray = ByteArray(0), val location: String? = null)
    private companion object {
        const val MAX_REDIRECTS = 3
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

internal fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
    first.scheme == second.scheme && first.host == second.host && first.port == second.port

package com.hesier.lumy.jellyfin

import com.hesier.lumy.core.HttpStatusException
import com.hesier.lumy.core.ServerKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** 客户端身份。deviceId 必须稳定，服务端用它区分设备、关联转码任务 */
data class JellyfinClientInfo(
    val product: String,
    val version: String,
    val deviceId: String,
    val device: String,
)

class JellyfinHttpException(code: Int, message: String) : HttpStatusException(code, message)

/**
 * Jellyfin 和 Emby 共用的 HTTP 层（Jellyfin 从 Emby 分叉而来，接口基本一致）。
 * 差别主要在认证头：Jellyfin 用标准的 Authorization，Emby 用 X-Emby-Authorization。
 */
class JellyfinClient(
    val info: JellyfinClientInfo,
    val http: OkHttpClient = defaultHttpClient(),
) {
    /** 两家的 JSON 字段都是大驼峰（Id、Name、UserData…），统一转换，DTO 里就不用逐个写 @SerialName */
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        namingStrategy = JsonNamingStrategy { _, _, name -> name.replaceFirstChar(Char::uppercaseChar) }
    }

    fun request(url: String, kind: ServerKind, token: String?, deviceId: String = info.deviceId): Request.Builder {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        headers(kind, token, deviceId).forEach { (k, v) -> builder.header(k, v) }
        return builder
    }

    /** 每个请求都要带的认证头；播放器的数据源也用这一套。[deviceId] 必须和拿到 token 时的一致 */
    fun headers(kind: ServerKind, token: String?, deviceId: String = info.deviceId): Map<String, String> {
        val auth = buildString {
            append("MediaBrowser ")
            append(listOf("Client" to info.product, "Device" to info.device, "DeviceId" to deviceId, "Version" to info.version)
                .joinToString(", ") { (k, v) -> "$k=\"${v.headerSafe()}\"" })
            if (token != null) append(", Token=\"$token\"")
        }
        return if (kind == ServerKind.EMBY) {
            buildMap {
                put("X-Emby-Authorization", auth)
                if (token != null) put("X-Emby-Token", token)
            }
        } else {
            mapOf("Authorization" to auth)
        }
    }

    fun jsonBody(body: String): RequestBody = body.toRequestBody(JSON_TYPE)

    suspend inline fun <reified T> fetch(request: Request, client: OkHttpClient = http): T {
        val body = execute(request, client)
        return withContext(Dispatchers.Default) { json.decodeFromString<T>(body) }
    }

    suspend fun execute(request: Request, client: OkHttpClient = http): String =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    // 读正文时断网 / 超时抛出的异常 OkHttp 只记日志不再回调，必须自己转给协程，否则请求会一直挂起
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw JellyfinHttpException(it.code, "HTTP ${it.code} ${request.url.encodedPath}")
                            it.body?.string().orEmpty()
                        }
                    }
                    cont.resumeWith(result)
                }
            })
        }

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

/** 头部值放在引号里，去掉会破坏格式的字符；OkHttp 也拒绝非 ASCII（设备名可能含中文） */
private fun String.headerSafe(): String = filter { it.code in 32..126 && it != '"' && it != ',' }.ifBlank { "Android" }

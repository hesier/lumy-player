package com.hesier.lumy.plex

import com.hesier.lumy.core.HttpStatusException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** 客户端身份信息，Plex 会在“设备”列表里展示它，并用 clientId 区分不同设备 */
data class PlexClientInfo(
    val product: String,
    val version: String,
    val clientId: String,
    val platformVersion: String,
    val device: String,
    /** 如 zh-CN，服务端据此返回本地化的行标题（“继续观看”等） */
    val language: String = "en",
)

class PlexHttpException(code: Int, message: String) : HttpStatusException(code, message)

/** 所有 Plex 请求共用的 HTTP 层：统一加身份头、要求 JSON 响应 */
class PlexClient(
    val info: PlexClientInfo,
    val http: OkHttpClient = defaultHttpClient(),
) {
    /** 界面语言可以在应用里随时切换，请求头按当前值发 */
    @Volatile var language: String = info.language

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun request(url: String, token: String?): Request.Builder {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        headers(token).forEach { (k, v) -> builder.header(k, v) }
        return builder
    }

    /** 每个请求都要带的身份头；播放器的数据源也用这一套 */
    fun headers(token: String?): Map<String, String> = buildMap {
        putAll(identityQuery())
        put("X-Plex-Platform-Version", info.platformVersion)
        put("X-Plex-Device-Name", "${info.product} (${info.device})".ascii())
        put("X-Plex-Provides", "player")
        put("X-Plex-Language", language)
        if (token != null) put("X-Plex-Token", token)
    }

    /** 转码地址必须把身份放进 URL 参数，因为 HLS 的子播放列表请求不一定带请求头 */
    fun identityQuery(): Map<String, String> = mapOf(
        "X-Plex-Product" to info.product,
        "X-Plex-Version" to info.version,
        "X-Plex-Client-Identifier" to info.clientId,
        "X-Plex-Platform" to "Android",
        "X-Plex-Device" to info.device.ascii(),
    )

    suspend inline fun <reified T> fetch(request: Request, client: OkHttpClient = http): T {
        val body = execute(request, client)
        return json.decodeFromString<T>(body)
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
                            if (!it.isSuccessful) throw PlexHttpException(it.code, "HTTP ${it.code} ${request.url.encodedPath}")
                            it.body?.string().orEmpty()
                        }
                    }
                    cont.resumeWith(result)
                }
            })
        }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

/** OkHttp 拒绝非 ASCII 的头部值，设备名可能含中文 */
private fun String.ascii(): String = filter { it.code in 32..126 }.ifBlank { "Android" }

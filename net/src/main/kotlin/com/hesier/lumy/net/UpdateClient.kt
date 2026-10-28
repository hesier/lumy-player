package com.hesier.lumy.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** 发布工作流随 APK 一起上传的 update.json */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    /** Release 里 APK 附件的文件名 */
    val apk: String,
    val size: Long,
    val sha256: String,
    /** 更新说明，键是 en / zh */
    val notes: Map<String, String> = emptyMap(),
)

/** 下载的文件大小或校验和对不上（镜像返回了错误页面、文件被截断或篡改） */
class UpdateChecksumException(message: String) : IOException(message)

/**
 * 从 GitHub Release 检查和下载新版本。
 * 版本信息不走 api.github.com，而是和 APK 一样从 Release 附件取：这样两者都能通过同一个镜像下载，也没有 API 的频率限制。
 * [mirror] 是加在原地址前面的代理前缀（如 https://ghfast.top/），null 表示直连 GitHub。
 */
class UpdateClient(
    private val http: OkHttpClient,
    private val repo: String,
    private val github: String = "https://github.com",
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun infoUrl(mirror: String?) = withMirror("$github/$repo/releases/latest/download/update.json", mirror)

    fun apkUrl(info: UpdateInfo, mirror: String?) =
        withMirror("$github/$repo/releases/download/v${info.versionName}/${info.apk}", mirror)

    suspend fun latest(mirror: String?): UpdateInfo = withContext(Dispatchers.IO) {
        // 镜像和 CDN 可能缓存旧的 update.json
        val request = Request.Builder().url(infoUrl(mirror)).cacheControl(CacheControl.FORCE_NETWORK).build()
        http.newCall(request).execute().use { response ->
            json.decodeFromString(UpdateInfo.serializer(), response.checkedBody().string())
        }
    }

    /** 下载到 [target]，边下边算校验和；先写临时文件，校验通过才改名，失败时不留下半个文件 */
    suspend fun download(info: UpdateInfo, mirror: String?, target: File, onProgress: (downloaded: Long, total: Long) -> Unit) =
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            val partial = File(target.path + ".part")
            val call = http.newCall(Request.Builder().url(apkUrl(info, mirror)).build())
            try {
                call.execute().use { response ->
                    val body = response.checkedBody()
                    val digest = MessageDigest.getInstance("SHA-256")
                    var downloaded = 0L
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                downloaded += n
                                onProgress(downloaded, info.size)
                            }
                        }
                    }
                    if (downloaded != info.size) throw UpdateChecksumException("Expected ${info.size} bytes, got $downloaded")
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!sha.equals(info.sha256, ignoreCase = true)) throw UpdateChecksumException("SHA-256 mismatch")
                }
                target.delete()
                if (!partial.renameTo(target)) throw IOException("Cannot move ${partial.name}")
            } catch (e: Throwable) {
                call.cancel()
                partial.delete()
                throw e
            }
            target
        }

    private fun Response.checkedBody() = if (isSuccessful) body!! else throw IOException("HTTP $code ${request.url}")

    companion object {
        /** 镜像前缀没写协议时补 https://，末尾统一一个斜杠 */
        fun withMirror(url: String, mirror: String?): String {
            val prefix = mirror?.trim()?.takeIf { it.isNotEmpty() } ?: return url
            val normalized = if (prefix.contains("://")) prefix else "https://$prefix"
            return normalized.trimEnd('/') + "/" + url
        }
    }
}

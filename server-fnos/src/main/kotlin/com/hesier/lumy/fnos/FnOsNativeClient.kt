package com.hesier.lumy.fnos

import com.hesier.lumy.core.ServerSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random

/** Uses the server's public web protocol without bundling its application credentials. */
internal class FnOsNativeClient(
    private val session: ServerSession,
    private val client: FnOsClient,
) {
    private val base = session.baseUrl.trimEnd('/').toHttpUrl()
    private val root = "${session.baseUrl.trimEnd('/')}/v/".toHttpUrl()
    private val api = root.resolve("api/v1/")!!
    private val http = client.http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val mutex = Mutex()
    private var protocol: Protocol? = null

    /** [path] is relative to /v/api/v1/; absolute native paths are also accepted. */
    suspend fun get(path: String): JsonElement = request(path, null)

    suspend fun post(path: String, body: JsonObject): JsonElement = request(path, body)

    suspend fun delete(path: String, body: JsonObject): JsonElement = request(path, body, "DELETE")

    private suspend fun request(path: String, body: JsonObject?, method: String = "POST"): JsonElement {
        val url = apiUrl(path)
        val signing = mutex.withLock { protocol ?: bootstrap().also { protocol = it } }
        val raw = body?.toString()
        val nonce = Random.nextInt(100000, 1000000).toString()
        val timestamp = System.currentTimeMillis().toString()
        val content = raw ?: canonicalQuery(url)
        val sign = md5(listOf(signing.application, url.encodedPath, nonce, timestamp, md5(content), signing.key)
            .joinToString("_"))
        val request = Request.Builder().url(url)
            .header("Authorization", session.token)
            .header("X-Trim-Client", "web")
            .header("authx", "nonce=$nonce&timestamp=$timestamp&sign=$sign")
        signing.version?.let { request.header("X-Trim-Client-Version", it) }
        if (raw != null) request.method(method, client.jsonBody(raw))
        val result = client.json.parseToJsonElement(client.execute(request.build(), http)).jsonObject
        if (result["code"]?.jsonPrimitive?.intOrNull != 0) {
            throw IOException("fnOS native request failed (${result["code"]}): ${url.encodedPath}")
        }
        return result["data"] ?: JsonNull
    }

    private fun apiUrl(path: String): HttpUrl {
        val relative = when {
            path.startsWith("/v/api/v1/") -> path.removePrefix("/v/api/v1/")
            path.startsWith("api/v1/") -> path.removePrefix("api/v1/")
            else -> path
        }
        val url = api.resolve(relative) ?: throw IOException("Invalid fnOS native path")
        if (!sameOrigin(url) || !url.encodedPath.startsWith(api.encodedPath) || url.fragment != null ||
            relative.startsWith('/') || relative.contains("://") || url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw IOException("Invalid fnOS native path")
        }
        return url
    }

    private fun canonicalQuery(url: HttpUrl): String = url.queryParameterNames.sorted().mapNotNull { key ->
        val value = url.queryParameterValues(key).lastOrNull { it != null && it != "undefined" && it != "null" }
        if (value == null) null else "$key=$value"
    }.joinToString("&")

    private suspend fun bootstrap(): Protocol {
        val pending = ArrayDeque<HttpUrl>().apply { add(root) }
        val visited = mutableSetOf<HttpUrl>()
        while (pending.isNotEmpty() && visited.size < MAX_BOOTSTRAP_FILES) {
            val url = pending.removeFirst()
            if (!visited.add(url)) continue
            val source = client.execute(Request.Builder().url(url).build(), http)
            if (source.contains("authx") && source.contains("nonce=")) return parseProtocol(source)
            SCRIPT_REFERENCE.findAll(source).forEach { match ->
                val reference = match.groups[1]?.value ?: match.groups[2]?.value ?: return@forEach
                val script = url.resolve(reference) ?: return@forEach
                if (sameOrigin(script) && script.username.isEmpty() && script.password.isEmpty() &&
                    script.encodedPath.endsWith(".js") && script.fragment == null) pending.add(script)
            }
        }
        throw IOException("fnOS web signature bootstrap not found")
    }

    private fun parseProtocol(source: String): Protocol {
        val application = SIGNATURE_INPUT.find(source)?.groupValues?.get(1)
            ?: throw IOException("Unsupported fnOS web signature format")
        val keyVariable = Regex("""apiKey:\s*(\w+)""").find(source)?.groupValues?.get(1)
            ?: throw IOException("fnOS web signing key reference not found")
        val keySource = Regex("""${Regex.escape(keyVariable)}=\(\(\)=>\{(.{1,600}?)\}\)\(\)""")
            .find(source)?.groupValues?.get(1) ?: throw IOException("Unsupported fnOS web key format")
        val numbers = Regex("""new Uint8Array\(\[([\d,\s]+)]\)""").find(keySource)?.groupValues?.get(1)
            ?: throw IOException("fnOS web key data not found")
        val mask = Regex("""\[\w+]=\w+\[\w+]\^(\d+)""").find(keySource)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IOException("fnOS web key encoding not found")
        val key = numbers.split(',').map { value ->
            val number = value.trim().toIntOrNull()?.takeIf { it in 0..255 }
                ?: throw IOException("Invalid fnOS web key encoding")
            (number xor mask).toByte()
        }.toByteArray().toString(Charsets.UTF_8)
        val versionVariable = Regex("""(\w+)=`X-Trim-Client-Version`""").find(source)?.groupValues?.get(1)
        val version = versionVariable?.let {
            Regex("""\[${Regex.escape(it)}]:`([^`]+)`""").find(source)?.groupValues?.get(1)
        }
        return Protocol(application, key, version)
    }

    private fun sameOrigin(url: HttpUrl): Boolean =
        url.scheme == base.scheme && url.host == base.host && url.port == base.port

    private data class Protocol(val application: String, val key: String, val version: String?)

    companion object {
        private const val MAX_BOOTSTRAP_FILES = 24
        private val SCRIPT_REFERENCE = Regex("""(?:src|href)=["']([^"']+\.js)["']|from["`]([^"`]+\.js)["`]""")
        private val SIGNATURE_INPUT = Regex("""\[`([^`]+)`,\w+,\w+,\w+,\w+,\w+]\.join\(`_`\)""")
        private fun md5(value: String): String = MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

package com.hesier.lumy.jellyfin

import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/** 用户手动输入地址，用户名密码登录。两家没有类似 plex.tv 的账号层，地址就是用户填的那个 */
class JellyfinConnector(private val client: JellyfinClient) {

    /** 找到的服务器；[kind] 以服务器实际返回为准（用户选错了类型也能连上） */
    data class Server(val baseUrl: String, val kind: ServerKind, val info: PublicSystemInfo)

    suspend fun probe(address: String): Server {
        val base = normalizeAddress(address) ?: throw IllegalArgumentException("Invalid server address: $address")
        val info = client.fetch<PublicSystemInfo>(client.request("$base/System/Info/Public", ServerKind.JELLYFIN, null).build())
        val kind = if (info.productName?.contains("Jellyfin", ignoreCase = true) == true) ServerKind.JELLYFIN else ServerKind.EMBY
        return Server(base, kind, info)
    }

    /**
     * 密码错误时服务端返回 401。每次登录用新的设备标识：同一台服务器可以添加多次，
     * 共用标识的话后一次登录会让前一次的 token 失效
     */
    suspend fun signIn(server: Server, username: String, password: String): ServerSession {
        val body = client.json.encodeToString(AuthenticateRequest.serializer(), AuthenticateRequest(username, password))
        val deviceId = "${client.info.deviceId}-${UUID.randomUUID().toString().take(8)}"
        val result = client.fetch<AuthenticationResult>(
            client.request("${server.baseUrl}/Users/AuthenticateByName", server.kind, null, deviceId)
                .post(client.jsonBody(body))
                .build(),
        )
        return ServerSession(
            kind = server.kind,
            serverId = result.serverId ?: server.info.id,
            name = server.info.serverName.ifBlank { server.baseUrl },
            baseUrl = server.baseUrl,
            token = result.accessToken,
            userId = result.user.id,
            deviceId = deviceId,
        )
    }

    companion object {
        /** 两家的默认端口都是 8096 */
        const val DEFAULT_PORT = 8096

        /**
         * 补全用户输入：没写协议用 http，http 没写端口用 8096；保留路径（反向代理的子路径，如 /jellyfin）。
         */
        fun normalizeAddress(input: String): String? {
            val raw = input.trim().trimEnd('/')
            if (raw.isEmpty()) return null
            val withScheme = if (raw.contains("://")) raw else "http://$raw"
            val authority = withScheme.substringAfter("://").substringBefore('/')
            // IPv6 地址本身带冒号，只看方括号后面
            val hasPort = authority.substringAfterLast(']').contains(':')
            val url = withScheme.toHttpUrlOrNull() ?: return null
            val port = if (!hasPort && url.scheme == "http") DEFAULT_PORT else url.port
            return url.newBuilder().port(port).build().toString().trimEnd('/')
        }
    }
}

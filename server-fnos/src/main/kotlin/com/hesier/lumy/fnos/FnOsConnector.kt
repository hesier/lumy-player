package com.hesier.lumy.fnos

import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/** Connects to the fnOS compatibility endpoint using username/password authentication. */
class FnOsConnector(private val client: FnOsClient) {

    data class Server(val baseUrl: String, val info: PublicSystemInfo) {
        val kind: ServerKind get() = ServerKind.FNOS
    }

    suspend fun probe(address: String): Server {
        val base = normalizeAddress(address) ?: throw IllegalArgumentException("Invalid server address: $address")
        val info = client.fetch<PublicSystemInfo>(client.request("$base/System/Info/Public", null).build())
        return Server(base, info)
    }

    // Separate logins need distinct device identities to avoid invalidating an earlier token.
    suspend fun signIn(server: Server, username: String, password: String): ServerSession {
        val body = client.json.encodeToString(AuthenticateRequest.serializer(), AuthenticateRequest(username, password))
        val deviceId = "${client.info.deviceId}-${UUID.randomUUID().toString().take(8)}"
        val result = client.fetch<AuthenticationResult>(
            client.request("${server.baseUrl}/Users/AuthenticateByName", null, deviceId)
                .post(client.jsonBody(body))
                .build(),
        )
        return ServerSession(
            kind = ServerKind.FNOS,
            serverId = result.serverId ?: server.info.id,
            name = server.info.serverName.ifBlank { server.baseUrl },
            baseUrl = server.baseUrl,
            token = result.accessToken,
            userId = result.user.id,
            deviceId = deviceId,
        )
    }

    companion object {
        const val DEFAULT_PORT = 8005

        /** Preserve proxy paths and explicit ports; only plain HTTP defaults to port 8005. */
        fun normalizeAddress(input: String): String? {
            val raw = input.trim().trimEnd('/')
            if (raw.isEmpty()) return null
            val withScheme = if (raw.contains("://")) raw else "http://$raw"
            val authority = withScheme.substringAfter("://").substringBefore('/')
            // IPv6 colons are inside brackets, so inspect only the suffix.
            val hasPort = authority.substringAfterLast(']').contains(':')
            val url = withScheme.toHttpUrlOrNull() ?: return null
            val port = if (!hasPort && url.scheme == "http") DEFAULT_PORT else url.port
            return url.newBuilder().port(port).build().toString().trimEnd('/')
        }
    }
}

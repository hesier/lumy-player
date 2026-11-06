package com.hesier.lumy.jellyfin

import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID

class QuickConnectException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { DISABLED, EXPIRED }
}

/** A pairing attempt retains its identity until the access token has been issued. */
class JellyfinQuickConnect internal constructor(
    private val client: JellyfinClient,
    private val server: JellyfinConnector.Server,
) {
    class Pairing internal constructor(
        val code: String,
        internal val secret: String,
        internal val deviceId: String,
    )

    data class Login(val session: ServerSession, val username: String)

    suspend fun initiate(): Pairing {
        if (!client.fetch<Boolean>(request("QuickConnect/Enabled").build())) {
            throw QuickConnectException(QuickConnectException.Reason.DISABLED)
        }
        val deviceId = "${client.info.deviceId}-${UUID.randomUUID().toString().take(8)}"
        val state = try {
            client.fetch<QuickConnectState>(request("QuickConnect/Initiate", deviceId).post(client.jsonBody("")).build())
        } catch (error: JellyfinHttpException) {
            if (error.code != 404 && error.code != 405) throw mapError(error)
            try {
                client.fetch<QuickConnectState>(request("QuickConnect/Initiate", deviceId).build())
            } catch (legacyError: JellyfinHttpException) {
                throw mapError(legacyError)
            }
        }
        require(state.code.isNotBlank() && state.secret.isNotBlank()) { "Invalid Quick Connect response" }
        return Pairing(state.code, state.secret, deviceId)
    }

    suspend fun awaitLogin(pairing: Pairing): Login = withTimeoutOrNull(PAIRING_TIMEOUT_MS) {
        while (true) {
            val url = "${server.baseUrl}/QuickConnect/Connect".toHttpUrl().newBuilder()
                .addQueryParameter("secret", pairing.secret).build()
            val state = try {
                client.fetch<QuickConnectState>(client.request(url.toString(), server.kind, null, pairing.deviceId).build())
            } catch (error: JellyfinHttpException) {
                throw mapError(error)
            }
            if (state.authenticated) return@withTimeoutOrNull authenticate(pairing)
            delay(POLL_INTERVAL_MS)
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable")
    } ?: throw QuickConnectException(QuickConnectException.Reason.EXPIRED)

    private suspend fun authenticate(pairing: Pairing): Login {
        val body = client.json.encodeToString(QuickConnectAuthentication.serializer(), QuickConnectAuthentication(pairing.secret))
        val result = try {
            client.fetch<AuthenticationResult>(request("Users/AuthenticateWithQuickConnect", pairing.deviceId)
                .post(client.jsonBody(body)).build())
        } catch (error: JellyfinHttpException) {
            throw mapError(error)
        }
        return Login(ServerSession(
            kind = server.kind, serverId = result.serverId ?: server.info.id,
            name = server.info.serverName.ifBlank { server.baseUrl }, baseUrl = server.baseUrl,
            token = result.accessToken, userId = result.user.id, deviceId = pairing.deviceId,
        ), result.user.name)
    }

    private fun request(path: String, deviceId: String = client.info.deviceId) =
        client.request("${server.baseUrl}/$path", ServerKind.JELLYFIN, null, deviceId)

    private fun mapError(error: JellyfinHttpException): Exception = when (error.code) {
        401, 403 -> QuickConnectException(QuickConnectException.Reason.DISABLED)
        404, 410 -> QuickConnectException(QuickConnectException.Reason.EXPIRED)
        else -> error
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val PAIRING_TIMEOUT_MS = 5 * 60_000L
    }
}

@Serializable
internal data class QuickConnectState(
    val code: String = "",
    val secret: String = "",
    val authenticated: Boolean = false,
)

@Serializable
internal data class QuickConnectAuthentication(val secret: String)

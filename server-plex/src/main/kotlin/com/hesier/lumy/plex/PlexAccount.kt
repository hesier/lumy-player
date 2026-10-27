package com.hesier.lumy.plex

import okhttp3.RequestBody.Companion.toRequestBody

/** plex.tv 账号层：PIN 登录、列出账号下的服务器 */
class PlexAccount(private val client: PlexClient) {

    /** strong=false 才会返回用户在 plex.tv/link 能输入的 4 位短码 */
    suspend fun createPin(): PlexPin = client.fetch(
        client.request("$PLEX_TV/api/v2/pins?strong=false", token = null)
            .post(ByteArray(0).toRequestBody())
            .build(),
    )

    /** 用户在手机上完成授权后，返回的 [PlexPin.authToken] 不再为空 */
    suspend fun checkPin(id: Long): PlexPin =
        client.fetch(client.request("$PLEX_TV/api/v2/pins/$id", token = null).build())

    suspend fun servers(token: String): List<PlexResource> {
        val resources: List<PlexResource> = client.fetch(
            client.request("$PLEX_TV/api/v2/resources?includeHttps=1&includeRelay=1", token).build(),
        )
        return resources
            .filter { it.isServer && it.accessToken != null }
            .sortedWith(compareByDescending<PlexResource> { it.owned }.thenByDescending { it.presence }.thenBy { it.name })
    }

    /** 校验已保存的账号 token 是否仍有效 */
    suspend fun isTokenValid(token: String): Boolean = try {
        client.execute(client.request("$PLEX_TV/api/v2/user", token).build())
        true
    } catch (e: PlexHttpException) {
        if (e.code == 401) false else throw e
    }

    companion object {
        const val PLEX_TV = "https://plex.tv"
        const val LINK_URL = "plex.tv/link"
    }
}

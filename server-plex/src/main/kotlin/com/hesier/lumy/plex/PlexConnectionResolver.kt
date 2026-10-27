package com.hesier.lumy.plex

import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * 一台服务器通常有多个地址（局域网、公网、https 的 plex.direct 域名、relay）。
 * 同时探测所有地址，第一个响应后再给排名更靠前的地址一小段宽限时间，
 * 这样局域网可用时一定走局域网，又不必等所有超时。relay 有限速，只在直连全失败时用。
 */
class PlexConnectionResolver(private val client: PlexClient) {

    suspend fun resolve(resource: PlexResource): ServerSession? {
        val token = resource.accessToken ?: return null
        val candidates = withDirectIpFallback(resource)
            .filter { !(resource.httpsRequired && it.protocol != "https") }
            .sortedWith(
                compareBy<PlexConnection> { if (it.local) 0 else 1 }
                    .thenBy { if (it.protocol == "https") 0 else 1 }
                    .thenBy { if (it.ipv6) 1 else 0 },
            )
        val winner = race(candidates.filterNot { it.relay }, resource.clientIdentifier, token)
            ?: race(candidates.filter { it.relay }, resource.clientIdentifier, token)
            ?: return null
        return ServerSession(
            kind = ServerKind.PLEX,
            serverId = resource.clientIdentifier,
            name = resource.name,
            baseUrl = winner.uri.trimEnd('/'),
            token = token,
        )
    }

    /**
     * 很多路由器开了 DNS 重绑定保护，不解析指向内网 IP 的域名，`*.plex.direct` 的局域网地址就用不了，
     * 只能绕到公网。和官方客户端一样，给每个局域网 plex.direct 地址补一个直接用 IP 的 http 地址
     * （服务器要求 https 时会被上面的过滤去掉）。
     */
    private fun withDirectIpFallback(resource: PlexResource): List<PlexConnection> {
        val extra = resource.connections
            .filter { it.local && !it.relay && it.uri.contains(".plex.direct") && it.address != null }
            .map { c ->
                val host = if (c.ipv6) "[${c.address}]" else c.address
                c.copy(protocol = "http", uri = "http://$host:${c.port ?: 32400}")
            }
            .filter { e -> resource.connections.none { it.uri.trimEnd('/') == e.uri } }
        return resource.connections + extra
    }

    /** 已保存的地址是否仍可用，用于启动时决定是否需要重新探测 */
    suspend fun isReachable(session: ServerSession): Boolean =
        probe(session.baseUrl, session.serverId, session.token, timeoutSec = 5)

    private suspend fun race(candidates: List<PlexConnection>, machineId: String, token: String): PlexConnection? {
        if (candidates.isEmpty()) return null
        return coroutineScope {
            // 通道里收到的是探测成功的候选下标，下标越小优先级越高
            val successes = Channel<Int>(Channel.UNLIMITED)
            val probes = candidates.mapIndexed { i, c ->
                launch {
                    if (probe(c.uri, machineId, token, timeoutSec = if (c.local) 4 else 10)) successes.send(i)
                }
            }
            launch {
                probes.joinAll()
                successes.close()
            }
            var best = successes.receiveCatching().getOrNull()
            if (best != null && best > 0) {
                withTimeoutOrNull(GRACE_MS) {
                    for (i in successes) {
                        if (i < best!!) best = i
                        if (best == 0) break
                    }
                }
            }
            coroutineContext.cancelChildren()
            best?.let { candidates[it] }
        }
    }

    private suspend fun probe(uri: String, machineId: String, token: String, timeoutSec: Long): Boolean {
        val http = client.http.newBuilder()
            .callTimeout(timeoutSec, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val request = client.request("${uri.trimEnd('/')}/identity", token).build()
        return runCatching { client.fetch<Envelope>(request, http).container.machineIdentifier }
            .getOrNull() == machineId
    }

    private companion object {
        const val GRACE_MS = 1_200L
    }
}

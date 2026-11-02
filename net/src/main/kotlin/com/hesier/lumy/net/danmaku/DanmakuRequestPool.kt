package com.hesier.lumy.net.danmaku

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import java.util.Locale

/** 只合并在途 GET；最后一个等待者离开就取消，不承担结果缓存。 */
internal object DanmakuRequestPool {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = HashMap<Key, Entry>()

    suspend fun get(client: OkHttpClient, url: String, headers: Map<String, String>,
        request: suspend () -> ByteArray): ByteArray {
        val key = Key(client, url, headers.entries.map { it.key.lowercase(Locale.ROOT) to it.value }
            .sortedWith(compareBy({ it.first }, { it.second })))
        val entry = synchronized(lock) {
            val current = pending[key]?.takeUnless { it.result.isCompleted } ?: create(key, request)
            current.waiters++
            current
        }
        try {
            entry.result.start()
            // 每位调用者独立持有结果，避免修改数组影响另一位调用者。
            return entry.result.await().copyOf()
        } finally {
            val cancel = synchronized(lock) {
                entry.waiters--
                if (entry.waiters == 0 && !entry.result.isCompleted) {
                    if (pending[key] === entry) pending.remove(key)
                    true
                } else false
            }
            if (cancel) entry.result.cancel()
        }
    }

    private fun create(key: Key, request: suspend () -> ByteArray): Entry {
        val entry = Entry(scope.async(start = CoroutineStart.LAZY) { request() })
        pending[key] = entry
        entry.result.invokeOnCompletion {
            synchronized(lock) { if (pending[key] === entry) pending.remove(key) }
        }
        return entry
    }

    // 不输出此 key；路径和请求头可能包含私有令牌。
    private data class Key(val client: OkHttpClient, val url: String, val headers: List<Pair<String, String>>)
    private class Entry(val result: Deferred<ByteArray>, var waiters: Int = 0)
}

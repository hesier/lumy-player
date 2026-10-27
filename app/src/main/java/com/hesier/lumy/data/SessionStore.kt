package com.hesier.lumy.data

import android.content.Context
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 已添加的服务器。[id] 是本机生成的，同一台服务器用不同账号添加也能区分；
 * [accountToken] 是 plex.tv 账号 token，服务器地址变了时用它重新获取地址。
 */
data class SavedServer(
    val id: String,
    val session: ServerSession,
    val accountToken: String? = null,
    /** Jellyfin / Emby 登录的用户名，同一台服务器加了多个用户时用来区分 */
    val userName: String? = null,
    /** 用户自己起的名字，优先于服务端返回的名称 */
    val customName: String? = null,
) {
    val kind: ServerKind get() = session.kind

    val displayName: String get() = customName?.takeIf { it.isNotBlank() } ?: session.name
}

/** 已添加的服务器列表和当前服务器，数量很少，整个列表存成一段 JSON */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    /** 设备唯一标识，服务器用它区分设备，必须稳定不变 */
    val clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_CLIENT_ID, it).apply() }

    var servers: List<SavedServer>
        get() = prefs.getString(KEY_SERVERS, null)?.let(::decode).orEmpty()
        private set(value) = prefs.edit().putString(KEY_SERVERS, encode(value)).apply()

    var currentId: String?
        get() = prefs.getString(KEY_CURRENT, null)
        set(value) = prefs.edit().putString(KEY_CURRENT, value).apply()

    /** 当前服务器；保存的 id 失效（被删除）时取第一台 */
    val current: SavedServer?
        get() {
            val all = servers
            return all.firstOrNull { it.id == currentId } ?: all.firstOrNull()
        }

    /** 同一台服务器可以添加多次（例如不同用户、不同地址），每次都是新的一项 */
    fun add(server: SavedServer) {
        servers = servers + server
    }

    /** 按 id 替换（换了地址或 token），保留在列表里的位置 */
    fun update(server: SavedServer) {
        servers = servers.map { if (it.id == server.id) server else it }
    }

    fun remove(id: String) {
        servers = servers.filterNot { it.id == id }
        if (currentId == id) currentId = servers.firstOrNull()?.id
    }

    /**
     * 旧版本只保存一台 Plex 服务器（分散在几个键里），转成列表里的一项。
     * @return 转换出的服务器 id，没有旧数据时为 null
     */
    fun migrateLegacy(): String? {
        if (prefs.contains(KEY_SERVERS)) return null
        val kind = prefs.getString(LEGACY_KIND, null)?.let { runCatching { ServerKind.valueOf(it) }.getOrNull() }
        val serverId = prefs.getString(LEGACY_SERVER_ID, null)
        val baseUrl = prefs.getString(LEGACY_BASE_URL, null)
        val token = prefs.getString(LEGACY_SERVER_TOKEN, null)
        val accountToken = prefs.getString(LEGACY_PLEX_TOKEN, null)
        val migrated = if (kind != null && serverId != null && baseUrl != null && token != null) {
            val session = ServerSession(kind, serverId, prefs.getString(LEGACY_SERVER_NAME, null).orEmpty(), baseUrl, token)
            SavedServer(UUID.randomUUID().toString(), session, accountToken)
        } else {
            null
        }
        prefs.edit().apply {
            listOf(LEGACY_KIND, LEGACY_SERVER_ID, LEGACY_SERVER_NAME, LEGACY_BASE_URL, LEGACY_SERVER_TOKEN, LEGACY_PLEX_TOKEN).forEach(::remove)
            putString(KEY_SERVERS, encode(listOfNotNull(migrated)))
            putString(KEY_CURRENT, migrated?.id)
        }.apply()
        return migrated?.id
    }

    private fun encode(list: List<SavedServer>): String = JSONArray().apply {
        list.forEach { s ->
            put(
                JSONObject()
                    .put("id", s.id)
                    .put("kind", s.kind.name)
                    .put("serverId", s.session.serverId)
                    .put("name", s.session.name)
                    .put("baseUrl", s.session.baseUrl)
                    .put("token", s.session.token)
                    .putOpt("userId", s.session.userId)
                    .putOpt("deviceId", s.session.deviceId)
                    .putOpt("accountToken", s.accountToken)
                    .putOpt("userName", s.userName)
                    .putOpt("customName", s.customName),
            )
        }
    }.toString()

    /** 解析失败的项（例如以后删掉了某种服务器类型）直接跳过 */
    private fun decode(raw: String): List<SavedServer> {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                SavedServer(
                    id = o.getString("id"),
                    session = ServerSession(
                        kind = ServerKind.valueOf(o.getString("kind")),
                        serverId = o.getString("serverId"),
                        name = o.optString("name"),
                        baseUrl = o.getString("baseUrl"),
                        token = o.getString("token"),
                        userId = o.optStringOrNull("userId"),
                        deviceId = o.optStringOrNull("deviceId"),
                    ),
                    accountToken = o.optStringOrNull("accountToken"),
                    userName = o.optStringOrNull("userName"),
                    customName = o.optStringOrNull("customName"),
                )
            }.getOrNull()
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)

    private companion object {
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_SERVERS = "servers"
        const val KEY_CURRENT = "current_server"
        const val LEGACY_PLEX_TOKEN = "plex_token"
        const val LEGACY_KIND = "server_kind"
        const val LEGACY_SERVER_ID = "server_id"
        const val LEGACY_SERVER_NAME = "server_name"
        const val LEGACY_BASE_URL = "server_url"
        const val LEGACY_SERVER_TOKEN = "server_token"
    }
}

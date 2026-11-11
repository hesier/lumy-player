package com.hesier.lumy.data.backup

import com.hesier.lumy.core.ServerKind
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal object BackupSchema {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val FORMAT = "lumy-backup"
    const val VERSION = 1
    private val serverFields = setOf("id", "kind", "serverId", "name", "baseUrl", "token", "userId",
        "deviceId", "accountToken", "userName", "customName", "plexHome")

    fun parse(bytes: ByteArray): JSONObject {
        require(bytes.size in 1..MAX_BYTES) { "Invalid backup size" }
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val root = JSONObject(text)
        require(string(root, "format") == FORMAT) { "Unknown backup format" }
        require(integer(root, "version") == VERSION.toLong()) { "Unsupported backup version" }
        require(integer(root, "createdAt") > 0) { "Invalid backup timestamp" }
        string(root, "appVersion")
        BackupSettings.validate(root.getJSONObject("settings"), false)
        val servers = root.getJSONArray("servers")
        require(servers.length() <= 1000) { "Too many servers" }
        val ids = (0 until servers.length()).map { i ->
            validateServer(servers.getJSONObject(i))
        }
        require(ids.distinct().size == ids.size) { "Duplicate server IDs" }
        val current = root.get("currentServer")
        require(current == JSONObject.NULL || current is String && current in ids) { "Invalid current server" }
        validateScoped(root.getJSONObject("serverSettings"), ids.toSet(), false)
        validateScoped(root.getJSONObject("trackMemory"), ids.toSet(), true)
        return root
    }

    fun validateServer(server: JSONObject): String {
        require(server.keys().asSequence().all { it in serverFields }) { "Unknown server field" }
        val id = string(server, "id")
        require(id.isNotBlank() && id.length <= 128 && '|' !in id && ':' !in id) { "Invalid server ID" }
        ServerKind.valueOf(string(server, "kind"))
        require(string(server, "serverId").isNotBlank())
        string(server, "name")
        val uri = URI(string(server, "baseUrl"))
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Invalid server URL"
        }
        listOf("userId", "userName", "customName").forEach { if (server.has(it)) string(server, it) }
        require(string(server, "token").isNotBlank()) { "Server is not authenticated" }
        listOf("accountToken", "deviceId").forEach {
            if (server.has(it)) string(server, it)
        }
        if (server.has("plexHome")) {
            val home = server.getJSONObject("plexHome")
            require(home.keys().asSequence().all { it in setOf("accountToken", "userId", "userName", "thumb") })
            require(string(home, "accountToken").isNotBlank())
            require(string(home, "userId").isNotBlank())
            string(home, "userName")
            if (home.has("thumb")) string(home, "thumb")
            require(server.getString("kind") == ServerKind.PLEX.name)
        }
        return id
    }

    private fun validateScoped(values: JSONObject, ids: Set<String>, memory: Boolean) {
        values.keys().forEach { id ->
            require(id in ids) { "Orphaned server preferences" }
            val entries = values.getJSONObject(id)
            if (memory) entries.keys().forEach { key -> validateMemory(key, entries.get(key)) }
            else BackupSettings.validate(entries, true)
        }
    }

    fun validateMemory(key: String, value: Any) {
        require(key.length <= 4096 && Regex("^(part|show):.+:(audio|sub)$").matches(key)) { "Invalid track key" }
        require(value is String && value.length <= 8192) { "Invalid track value" }
        if (key.startsWith("show:") && key.endsWith(":sub")) {
            require(value == "off" || value.startsWith("forced:") ||
                value.startsWith("lang:") && value.length > 5) { "Invalid subtitle rule" }
        }
    }

    fun string(obj: JSONObject, key: String): String {
        val value = obj.get(key)
        require(value is String && value.length <= 8192) { "Invalid string: $key" }
        return value
    }

    private fun integer(obj: JSONObject, key: String): Long {
        val value = obj.get(key)
        require(BackupSettings.isInteger(value)) { "Invalid integer: $key" }
        return (value as Number).toLong()
    }
}

package com.hesier.lumy.data.backup

import android.content.Context
import com.hesier.lumy.BuildConfig
import com.hesier.lumy.data.SessionStore
import org.json.JSONArray
import org.json.JSONObject

class BackupStore(private val context: Context) {
    private val transaction = BackupTransaction(context)

    fun export(): ByteArray = synchronized(lock) {
        transaction.recover()
        val session = transaction.prefs("session")
        val servers = JSONArray(session.getString("servers", null) ?: "[]")
        val ids = (0 until servers.length()).map { index ->
            val server = servers.getJSONObject(index)
            if (server.getString("kind") != "PLEX" && server.isNull("deviceId")) {
                // Legacy tokens belong to the source device identity, not the destination's.
                server.put("deviceId", SessionStore(context).clientId)
            }
            BackupSchema.validateServer(server)
        }
        val settings = transaction.prefs("settings").all
        val global = JSONObject()
        BackupSettings.globalKeys.forEach { key -> settings[key]?.let { global.put(key, BackupSettings.jsonValue(it)) } }
        val scoped = JSONObject()
        for (id in ids) {
            val entries = JSONObject()
            BackupSettings.serverKeys.forEach { key ->
                val value = settings["$key:$id"] ?: if (key == "allow_transcode") settings[key] else null
                value?.let { entries.put(key, BackupSettings.jsonValue(it)) }
            }
            scoped.put(id, entries)
        }
        val current = session.getString("current_server", null)?.takeIf { it in ids }
        val root = JSONObject().put("format", BackupSchema.FORMAT).put("version", BackupSchema.VERSION)
            .put("createdAt", System.currentTimeMillis()).put("appVersion", BuildConfig.VERSION_NAME)
            .put("settings", global).put("servers", servers)
            .put("currentServer", current ?: JSONObject.NULL).put("serverSettings", scoped)
            .put("trackMemory", exportMemory(ids))
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        BackupSchema.parse(bytes)
        bytes
    }

    fun inspect(bytes: ByteArray): BackupData {
        val root = BackupSchema.parse(bytes)
        return BackupData(root.getLong("createdAt"), root.getString("appVersion"),
            root.getJSONArray("servers").length(), root.toString())
    }

    fun restore(data: BackupData, options: RestoreOptions) = synchronized(lock) {
        val root = BackupSchema.parse(data.payload.toByteArray(Charsets.UTF_8))
        if (!options.settings && !options.servers) return@synchronized
        transaction.run {
            restoreSettings(root, options)
            if (options.servers) restoreServers(root)
        }
    }

    fun recoverPendingRestore() = synchronized(lock) { transaction.recover() }

    private fun exportMemory(ids: List<String>): JSONObject {
        val root = JSONObject()
        val all = transaction.prefs("track_memory").all
        for (id in ids) {
            val entries = JSONObject()
            all.filterKeys { it.startsWith("$id|") }.forEach { (key, value) ->
                val shortKey = key.removePrefix("$id|")
                if (shortKey.startsWith("part:") && !shortKey.endsWith(":audio") && !shortKey.endsWith(":sub")) {
                    require(value is String) { "Invalid legacy track memory" }
                    val parts = value.split('|', limit = 2)
                    if (!all.containsKey("$key:audio") && parts[0].isNotEmpty()) entries.put("$shortKey:audio", parts[0])
                    if (!all.containsKey("$key:sub")) entries.put("$shortKey:sub", parts.getOrElse(1) { "" })
                } else {
                    requireNotNull(value)
                    BackupSchema.validateMemory(shortKey, value)
                    entries.put(shortKey, value)
                }
            }
            root.put(id, entries)
        }
        return root
    }

    private fun restoreSettings(root: JSONObject, options: RestoreOptions) {
        val prefs = transaction.prefs("settings")
        val editor = prefs.edit()
        if (options.settings) {
            val keys = BackupSettings.globalKeys.filter { options.deviceSettings || it !in BackupSettings.deviceKeys }
            keys.forEach(editor::remove)
            BackupSettings.validate(root.getJSONObject("settings"), false).filterKeys { it in keys }
                .forEach { (key, value) -> BackupTransaction.put(editor, key, value) }
        }
        if (options.servers) {
            prefs.all.keys.filter { it.substringBefore(':') in BackupSettings.serverKeys && ':' in it }.forEach(editor::remove)
            // The legacy fallback must not leak the previous server's preference into imported servers.
            editor.remove("allow_transcode").remove("hidden_sidebar").remove("sidebar_order")
            val scoped = root.getJSONObject("serverSettings")
            scoped.keys().forEach { id ->
                for ((key, value) in BackupSettings.validate(scoped.getJSONObject(id), true)) {
                    BackupTransaction.put(editor, "$key:$id", value)
                }
            }
        }
        check(editor.commit()) { "Cannot restore settings" }
    }

    private fun restoreServers(root: JSONObject) {
        val servers = root.getJSONArray("servers")
        val session = transaction.prefs("session").edit().putString("servers", servers.toString())
        if (root.isNull("currentServer")) session.remove("current_server")
        else session.putString("current_server", root.getString("currentServer"))
        check(session.commit()) { "Cannot restore servers" }
        val memory = transaction.prefs("track_memory").edit().clear()
        val scoped = root.getJSONObject("trackMemory")
        scoped.keys().forEach { id ->
            val entries = scoped.getJSONObject(id)
            entries.keys().forEach { key -> memory.putString("$id|$key", entries.getString(key)) }
        }
        check(memory.commit()) { "Cannot restore track memory" }
    }

    private companion object { val lock = Any() }
}

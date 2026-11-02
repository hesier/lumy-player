package com.hesier.lumy.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class BackupStoreTest(base: Context) {
    private var failCommits = 0
    private val testRoot = File(base.cacheDir, "backup-store-test")
    private val context = object : ContextWrapper(base) {
        override fun getFilesDir() = File(testRoot, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(testRoot, "no_backup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val delegate = super.getSharedPreferences("backup-store-test-$name", mode)
            return object : SharedPreferences by delegate {
                override fun edit(): SharedPreferences.Editor {
                    val editor = delegate.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun clear(): SharedPreferences.Editor {
                            editor.clear()
                            return this
                        }
                        override fun commit(): Boolean {
                            val success = editor.commit()
                            if (failCommits > 0) { failCommits--; return false }
                            return success
                        }
                    }
                }
            }
        }
    }
    private val store = BackupStore(context)
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun snapshot() = listOf("settings", "session", "track_memory").map { prefs(it).all }
    private val journal get() = File(context.noBackupFilesDir, "restore-pending.json")

    fun runAll() {
        reset(); preservesSessionsAndExcludesDeviceIdentity()
        reset(); roundTripsEveryServerKind()
        roundTripsBackupFiles()
        reset(); preservesLegacySessionDeviceAssociation()
        reset(); rejectsUnauthenticatedExport()
        reset(); restoresAssociationsAndSelectedScopes()
        reset(); validatesBeforeWriting()
        reset(); rollsBackFailedWrites()
        reset(); recoversInterruptedRestore()
        reset()
    }

    private fun reset() {
        failCommits = 0
        store.recoverPendingRestore()
        listOf("settings", "session", "track_memory").forEach { check(prefs(it).edit().clear().commit()) }
        val servers = JSONArray().put(JSONObject().put("id", "saved-a").put("kind", "FNOS")
            .put("serverId", "remote").put("name", "Test").put("baseUrl", "http://192.168.1.2:5666")
            .put("token", "SECRET").put("accountToken", "ACCOUNT_SECRET").put("deviceId", "fnos-device"))
        check(prefs("session").edit().putString("servers", servers.toString())
            .putString("current_server", "saved-a").putString("client_id", "local-device").commit())
        check(prefs("settings").edit().putString("theme", "LIGHT").putBoolean("passthrough", true)
            .putStringSet("broken_dovi_profiles", setOf("5")).putString("unknown", "not-exported")
            .putBoolean("allow_transcode:saved-a", false).putStringSet("hidden_sidebar:saved-a", setOf("library"))
            .putInt("bandwidth_mbps:saved-a", 20).putFloat("hold_speed", 2f).putLong("audio_delay_ms", 100L).commit())
        check(prefs("track_memory").edit().putString("saved-a|part:file:sub", "")
            .putString("saved-a|show:show:sub", "lang:eng").putString("orphan|part:file:audio", "ignored").commit())
    }

    private fun roundTripsBackupFiles() {
        val archive = BackupArchive(context)
        val expected = com.hesier.lumy.data.SessionStore(context).servers
        check(expected.size == 4)
        for (password in listOf("", "   ", "backup-password")) {
            val chars = password.toCharArray()
            try {
                val bytes = archive.export(chars)
                check(com.hesier.lumy.net.BackupCipher.isEncrypted(bytes) == password.isNotBlank())
                val data = archive.inspect(bytes, chars)
                check(data.serverCount == 4)
                check(prefs("session").edit().putString("servers", "[]").commit())
                store.restore(data, RestoreOptions(settings = false))
                check(expected == com.hesier.lumy.data.SessionStore(context).servers)
                if (password.isNotBlank()) check(runCatching { archive.inspect(bytes) }.isFailure)
                else check(bytes.toString(Charsets.UTF_8).contains("ACCOUNT_SECRET"))
            } finally { chars.fill('\u0000') }
        }
        check(runCatching { archive.inspect("invalid backup".toByteArray()) }.isFailure)
    }

    private fun preservesSessionsAndExcludesDeviceIdentity() {
        val text = store.export().toString(Charsets.UTF_8)
        check(text.contains("SECRET") && text.contains("fnos-device") && !text.contains("client_id"))
        check(!text.contains("broken_dovi_profiles") && !text.contains("not-exported") && !text.contains("orphan"))
        val data = store.inspect(text.toByteArray())
        check(data.serverCount == 1 && data.createdAt > 0)
        val full = JSONObject(store.export().toString(Charsets.UTF_8)).getJSONArray("servers").getJSONObject(0)
        check(full.getString("token") == "SECRET" && full.getString("deviceId") == "fnos-device")
    }

    private fun roundTripsEveryServerKind() {
        val template = JSONArray(prefs("session").getString("servers", null)).getJSONObject(0)
        val servers = JSONArray()
        listOf("PLEX", "JELLYFIN", "EMBY", "FNOS").forEach { kind ->
            servers.put(JSONObject(template.toString()).put("kind", kind).put("id", kind.lowercase())
                .put("userId", "user-$kind").put("userName", "name-$kind").put("customName", "custom-$kind"))
        }
        check(prefs("session").edit().putString("servers", servers.toString()).putString("current_server", "fnos").commit())
        val data = store.inspect(store.export())
        check(data.serverCount == 4)
        check(prefs("session").edit().putString("servers", "[]").commit())
        store.restore(data, RestoreOptions(settings = false))
        val restored = JSONArray(prefs("session").getString("servers", null))
        for (i in 0 until servers.length()) {
            val expected = servers.getJSONObject(i)
            val actual = restored.getJSONObject(i)
            expected.keys().forEach { key -> check(expected.get(key) == actual.get(key)) }
        }
        check(prefs("session").getString("current_server", null) == "fnos")
    }

    private fun preservesLegacySessionDeviceAssociation() {
        val template = JSONArray(prefs("session").getString("servers", null)).getJSONObject(0)
        val servers = JSONArray()
        listOf("JELLYFIN", "EMBY", "FNOS").forEach { kind ->
            servers.put(JSONObject(template.toString()).put("kind", kind).put("id", kind.lowercase())
                .apply { remove("deviceId") })
        }
        servers.getJSONObject(0).put("deviceId", JSONObject.NULL)
        servers.put(JSONObject(template.toString()).put("id", "modern"))
        servers.put(JSONObject(template.toString()).put("id", "plex").put("kind", "PLEX")
            .apply { remove("deviceId") })
        check(prefs("session").edit().putString("servers", servers.toString()).commit())
        val bytes = store.export()
        val exported = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("servers")
        for (i in 0..2) check(exported.getJSONObject(i).getString("deviceId") == "local-device")
        check(exported.getJSONObject(3).getString("deviceId") == "fnos-device")
        check(!exported.getJSONObject(4).has("deviceId"))
        check(!bytes.toString(Charsets.UTF_8).contains("client_id"))
        check(prefs("session").edit().putString("client_id", "target-device").commit())
        store.restore(store.inspect(bytes), RestoreOptions(settings = false))
        check(prefs("session").getString("client_id", null) == "target-device")
        val restored = JSONArray(prefs("session").getString("servers", null))
        for (i in 0..2) check(restored.getJSONObject(i).getString("deviceId") == "local-device")
        check(restored.getJSONObject(3).getString("deviceId") == "fnos-device")
    }

    private fun rejectsUnauthenticatedExport() {
        val servers = JSONArray(prefs("session").getString("servers", null))
        servers.getJSONObject(0).put("token", "")
        check(prefs("session").edit().putString("servers", servers.toString()).commit())
        check(runCatching { store.export() }.isFailure)
    }

    private fun restoresAssociationsAndSelectedScopes() {
        val data = store.inspect(store.export())
        check(prefs("settings").edit().putString("theme", "DARK").putBoolean("passthrough", false)
            .putBoolean("allow_transcode:saved-a", true).commit())
        check(prefs("track_memory").edit().clear().commit())
        store.restore(data, RestoreOptions(settings = true, servers = false))
        check(prefs("settings").getString("theme", null) == "LIGHT")
        check(!prefs("settings").getBoolean("passthrough", true))
        check(prefs("settings").getBoolean("allow_transcode:saved-a", false))
        check(prefs("track_memory").all.isEmpty())
        check(prefs("settings").edit().putString("theme", "DARK").commit())
        store.restore(data, RestoreOptions(settings = false, servers = true))
        check(prefs("settings").getString("theme", null) == "DARK")
        check(!prefs("settings").getBoolean("allow_transcode:saved-a", true))
        check(prefs("track_memory").getString("saved-a|part:file:sub", null) == "")
        check(prefs("track_memory").getString("saved-a|show:show:sub", null) == "lang:eng")
        val server = JSONArray(prefs("session").getString("servers", null)).getJSONObject(0)
        check(server.getString("id") == "saved-a" && server.getString("token") == "SECRET")
        check(prefs("session").getString("client_id", null) == "local-device")
        store.restore(data, RestoreOptions(deviceSettings = true))
        check(prefs("settings").getBoolean("passthrough", false))
        check(prefs("settings").getStringSet("broken_dovi_profiles", null) == setOf("5"))
    }

    private fun validatesBeforeWriting() {
        val original = store.export()
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("version", 999) }, { it.put("format", "other") },
            { it.getJSONObject("settings").put("theme", "UNKNOWN") },
            { it.getJSONObject("settings").put("hold_speed", "2.0") },
            { it.getJSONObject("settings").put("default_quality", 999) },
            { it.getJSONObject("settings").put("client_id", "injected") },
            { it.getJSONArray("servers").getJSONObject(0).put("kind", "UNKNOWN") },
            { it.getJSONArray("servers").getJSONObject(0).remove("serverId") },
            { it.put("currentServer", "missing") },
            { it.getJSONObject("trackMemory").put("orphan", JSONObject()) },
            { it.getJSONArray("servers").getJSONObject(0).put("token", "") },
        )
        mutations.forEach { mutate ->
            val changed = JSONObject(original.toString(Charsets.UTF_8)).also(mutate)
            val before = snapshot()
            check(runCatching { store.inspect(changed.toString().toByteArray()) }.isFailure)
            check(before == snapshot())
        }
        check(runCatching { store.inspect(ByteArray(BackupSchema.MAX_BYTES + 1)) }.isFailure)
    }

    private fun rollsBackFailedWrites() {
        val data = store.inspect(store.export())
        check(prefs("settings").edit().putString("theme", "DARK").commit())
        val before = snapshot()
        failCommits = 1
        check(runCatching { store.restore(data, RestoreOptions()) }.isFailure)
        check(before == snapshot())
        check(!journal.exists())
    }

    private fun recoversInterruptedRestore() {
        val data = store.inspect(store.export())
        check(prefs("settings").edit().putString("theme", "DARK").commit())
        val before = snapshot()
        failCommits = 2
        check(runCatching { store.restore(data, RestoreOptions()) }.isFailure)
        check(journal.exists())
        failCommits = 0
        BackupStore(context).recoverPendingRestore()
        check(before == snapshot())
        check(!journal.exists())
    }
}

package com.hesier.lumy.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import com.hesier.lumy.data.backup.BackupStore
import com.hesier.lumy.data.backup.RestoreOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Uses disposable preferences and files so no saved user session is read or changed. */
class PlexHomeSessionTest(base: Context) {
    private val namespace = "plex-home-test-${UUID.randomUUID()}"
    private val testRoot = File(base.cacheDir, namespace)
    private val context = object : ContextWrapper(base) {
        override fun getFilesDir() = File(testRoot, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(testRoot, "no_backup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("$namespace-$name", mode)
    }
    private val backup = BackupStore(context)
    private val home = PlexHomeIdentity("fixture-root-token", "child-id", "Child", "https://example.test/avatar")
    private val server = SavedServer(
        id = "saved-home",
        session = ServerSession(ServerKind.PLEX, "fixture-server", "Fixture", "http://192.0.2.1:32400", "fixture-server-token"),
        accountToken = "fixture-active-token",
        plexHome = home,
    )

    fun runAll() {
        try {
            reset(); preservesExistingSessionWithoutHome()
            reset(); migratesLegacySessionWithoutHome()
            reset(); roundTripsSeparateAccountTokens()
            reset(); roundTripsHomeBackup()
            reset(); roundTripsAutoSignInSetting()
            reset(); rejectsMalformedHomeBeforeWriting()
        } finally {
            reset()
        }
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun reset() {
        listOf("settings", "session", "track_memory").forEach {
            check(prefs(it).edit().clear().commit())
        }
    }

    private fun preservesExistingSessionWithoutHome() {
        val legacy = JSONObject().put("id", "legacy").put("kind", "PLEX")
            .put("serverId", "fixture-server").put("name", "Legacy")
            .put("baseUrl", "http://192.0.2.1:32400").put("token", "fixture-server-token")
            .put("accountToken", "fixture-original-account-token")
        check(prefs("session").edit().putString("servers", JSONArray().put(legacy).toString())
            .putString("current_server", "legacy").commit())
        val sessions = SessionStore(context)
        val saved = checkNotNull(sessions.current)
        check(saved.accountToken == "fixture-original-account-token")
        check(saved.plexHome == null)
        sessions.update(saved.copy(customName = "Renamed"))
        val reloaded = checkNotNull(SessionStore(context).current)
        check(reloaded.accountToken == saved.accountToken && reloaded.plexHome == null)
        check(reloaded.session.token == "fixture-server-token")
    }

    private fun migratesLegacySessionWithoutHome() {
        check(prefs("session").edit().putString("server_kind", "PLEX")
            .putString("server_id", "fixture-server").putString("server_name", "Legacy")
            .putString("server_url", "http://192.0.2.1:32400").putString("server_token", "fixture-server-token")
            .putString("plex_token", "fixture-original-account-token").commit())
        val sessions = SessionStore(context)
        check(sessions.migrateLegacy() != null)
        val saved = checkNotNull(SessionStore(context).current)
        check(saved.accountToken == "fixture-original-account-token")
        check(saved.session.token == "fixture-server-token" && saved.plexHome == null)
    }

    private fun roundTripsSeparateAccountTokens() {
        saveHome()
        val saved = checkNotNull(SessionStore(context).current)
        check(saved == server)
        val encoded = JSONArray(prefs("session").getString("servers", null)).getJSONObject(0)
        check(encoded.getString("accountToken") == "fixture-active-token")
        check(encoded.getString("token") == "fixture-server-token")
        check(encoded.getJSONObject("plexHome").getString("accountToken") == "fixture-root-token")
        SessionStore(context).update(saved.copy(plexHome = home.copy(thumb = null)))
        check(SessionStore(context).current?.plexHome == home.copy(thumb = null))
    }

    private fun roundTripsHomeBackup() {
        saveHome()
        val bytes = backup.export()
        val exported = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("servers").getJSONObject(0)
        check(exported.getString("accountToken") == server.accountToken)
        check(exported.getJSONObject("plexHome").getString("accountToken") == home.accountToken)
        val inspected = backup.inspect(bytes)
        check(inspected.serverCount == 1)
        check(prefs("session").edit().clear().putString("client_id", "fixture-destination-device").commit())
        backup.restore(inspected, RestoreOptions())
        check(SessionStore(context).current == server)
        check(SessionStore(context).currentId == server.id)
        check(SessionStore(context).clientId == "fixture-destination-device")
    }

    private fun roundTripsAutoSignInSetting() {
        saveHome()
        val settings = Settings(context)
        check(settings.plexHomeAutoSignIn)
        val defaultBackup = backup.inspect(backup.export())
        settings.plexHomeAutoSignIn = false
        check(!Settings(context).plexHomeAutoSignIn)
        val disabledBackup = backup.inspect(backup.export())
        settings.plexHomeAutoSignIn = true
        backup.restore(disabledBackup, RestoreOptions(settings = true, servers = false))
        check(!Settings(context).plexHomeAutoSignIn)
        backup.restore(defaultBackup, RestoreOptions(settings = true, servers = false))
        check(Settings(context).plexHomeAutoSignIn)
    }

    private fun rejectsMalformedHomeBeforeWriting() {
        saveHome()
        Settings(context).plexHomeAutoSignIn = false
        val original = backup.export().toString(Charsets.UTF_8)
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("plexHome", "invalid") },
            { it.put("plexHome", JSONObject.NULL) },
            { it.getJSONObject("plexHome").remove("accountToken") },
            { it.getJSONObject("plexHome").put("accountToken", " ") },
            { it.getJSONObject("plexHome").put("accountToken", 123) },
            { it.getJSONObject("plexHome").remove("userId") },
            { it.getJSONObject("plexHome").put("userId", "") },
            { it.getJSONObject("plexHome").remove("userName") },
            { it.getJSONObject("plexHome").put("thumb", true) },
            { it.getJSONObject("plexHome").put("unknown", "invalid") },
            { it.put("kind", "JELLYFIN") },
        )
        mutations.forEachIndexed { index, mutate ->
            val changed = JSONObject(original)
            mutate(changed.getJSONArray("servers").getJSONObject(0))
            val before = snapshot()
            check(runCatching { backup.inspect(changed.toString().toByteArray(Charsets.UTF_8)) }.isFailure) {
                "Malformed Home backup $index was accepted"
            }
            check(before == snapshot()) { "Malformed Home backup $index changed preferences" }
        }
    }

    private fun saveHome() {
        SessionStore(context).apply { add(server); currentId = server.id }
    }

    private fun snapshot() = listOf("settings", "session", "track_memory").map { prefs(it).all.toMap() }
}

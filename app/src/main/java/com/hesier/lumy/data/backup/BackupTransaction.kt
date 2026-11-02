package com.hesier.lumy.data.backup

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Journal remains until every preference file has been committed successfully. */
internal class BackupTransaction(private val context: Context) {
    private val journal = AtomicFile(File(context.noBackupFilesDir, "restore-pending.json"))
    private val names = listOf("settings", "session", "track_memory")
    fun prefs(name: String): SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun recover() {
        val bytes = try { journal.readFully() } catch (_: java.io.FileNotFoundException) { return }
        val snapshot = JSONObject(bytes.toString(Charsets.UTF_8))
        names.forEach { name ->
            val editor = prefs(name).edit().clear()
            val values = snapshot.getJSONObject(name)
            values.keys().forEach { key ->
                val entry = values.getJSONObject(key)
                val value = entry.get("value")
                val decoded = when (entry.getString("type")) {
                    "int" -> (value as Number).toInt()
                    "long" -> (value as Number).toLong()
                    "float" -> (value as Number).toFloat()
                    "set" -> (value as JSONArray).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    else -> value
                }
                put(editor, key, decoded)
            }
            check(editor.commit()) { "Cannot roll back $name" }
        }
        journal.delete()
    }

    fun run(write: () -> Unit) {
        recover()
        val snapshot = JSONObject()
        names.forEach { name ->
            val values = JSONObject()
            for ((key, value) in prefs(name).all) {
                requireNotNull(value)
                val type = when (value) {
                    is Int -> "int"
                    is Long -> "long"
                    is Float -> "float"
                    is Set<*> -> "set"
                    is Boolean -> "boolean"
                    else -> "string"
                }
                values.put(key, JSONObject().put("type", type).put("value", BackupSettings.jsonValue(value)))
            }
            snapshot.put(name, values)
        }
        val stream = journal.startWrite()
        try {
            stream.write(snapshot.toString().toByteArray(Charsets.UTF_8))
            journal.finishWrite(stream)
        } catch (error: Throwable) {
            journal.failWrite(stream)
            throw error
        }
        try {
            write()
            journal.delete()
        } catch (error: Throwable) {
            try { recover() } catch (rollback: Throwable) { error.addSuppressed(rollback) }
            throw error
        }
    }

    companion object {
        fun put(editor: SharedPreferences.Editor, key: String, value: Any) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.map { it as String }.toSet())
                else -> error("Unsupported preference type")
            }
        }
    }
}

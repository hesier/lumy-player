package com.hesier.lumy.tv

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat

/** Keeps tests away from the device's real launcher data and program preferences. */
open class WatchNextTestContext(base: Context) : ContextWrapper(base) {
    val provider = ProgramProvider()
    private val resolver = ContentResolver.wrap(provider)

    override fun getFilesDir() = java.io.File(super.getFilesDir(), "watch_next_test").apply { mkdirs() }

    override fun getContentResolver(): ContentResolver = resolver

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        super.getSharedPreferences("watch_next_test_$name", mode)
}

class ProgramProvider : ContentProvider() {
    val rows = linkedMapOf<Long, ContentValues>()
    private var nextId = 1L
    var queryFailure: Exception? = null
    var writeFailure: Exception? = null
    var nullQueries = false
    var queries = 0
    var writes = 0
    var beforeInsert: (() -> Unit)? = null

    override fun onCreate() = true
    override fun getType(uri: Uri) = "vnd.android.cursor.item/watch_next_program"

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        writes++
        writeFailure?.let { throw it }
        beforeInsert?.also { beforeInsert = null }?.invoke()
        val id = nextId++
        rows[id] = ContentValues(values).apply { put("_id", id) }
        return ContentUris.withAppendedId(TvContractCompat.WatchNextPrograms.CONTENT_URI, id)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        val row = rows[ContentUris.parseId(uri)] ?: return 0
        row.putAll(values)
        return 1
    }

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int =
        if (rows.remove(ContentUris.parseId(uri)) != null) 1 else 0

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?,
    ): Cursor? {
        queries++
        queryFailure?.let { throw it }
        if (nullQueries) return null
        val columns = projection ?: arrayOf("_id")
        val selected = uri.lastPathSegment?.toLongOrNull()?.let { listOfNotNull(rows[it]) } ?: rows.values
        return MatrixCursor(columns).apply {
            for (row in selected) addRow(columns.map { row[it] })
        }
    }
}

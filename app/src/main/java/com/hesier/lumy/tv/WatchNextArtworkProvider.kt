package com.hesier.lumy.tv

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Public, read-only access to generated launcher posters, never to source URLs or credentials. */
class WatchNextArtworkProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only artwork")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?,
    ): Cursor {
        val file = file(uri)
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }.toTypedArray()
        return MatrixCursor(columns).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) file.name else file.length() })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Read-only artwork")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only artwork")
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only artwork")

    private fun file(uri: Uri): File {
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != "art" || !segments[1].matches(Regex("[0-9a-f]{64}\\.jpg"))) {
            throw FileNotFoundException("Unknown artwork")
        }
        return File(WatchNextArtwork.directory(checkNotNull(context)), segments[1]).also {
            if (!it.isFile) throw FileNotFoundException("Artwork unavailable")
        }
    }
}

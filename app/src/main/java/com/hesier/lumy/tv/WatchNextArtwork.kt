package com.hesier.lumy.tv

import android.content.Context
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest

/** The launcher can read prepared images without receiving server credentials. */
internal object WatchNextArtwork {
    fun directory(context: Context) = File(context.filesDir, "watch_next_artwork")

    suspend fun prepare(context: Context, server: MediaServer, item: MediaItem, session: WatchNextSession): Uri? {
        val ref = item.thumb ?: item.backdrop ?: item.poster ?: return null
        return try {
            val url = server.imageUrl(ref, 640, 360)
            val uri = Uri.parse(url)
            // The launcher has its own cleartext policy; only public HTTPS can bypass Lumy.
            if (server.session.kind != ServerKind.FNOS && uri.scheme.equals("https", ignoreCase = true)) return uri
            withContext(Dispatchers.IO) {
                val key = "${session.serverId}:${session.generation}:$url"
                val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
                    .joinToString("") { "%02x".format(it) } + ".jpg"
                val dir = directory(context).apply { mkdirs() }
                val file = File(dir, name)
                if (!file.isFile) download(context, url, file, session)
                if (file.isFile) Uri.parse("content://${context.packageName}.watchnext.art/art/$name") else null
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            DebugLog.i("WatchNextManager", "Failed to prepare launcher artwork: ${e.message}")
            null
        }
    }

    private suspend fun download(context: Context, url: String, file: File, session: WatchNextSession) {
        val result = withTimeoutOrNull(5_000) {
            // Reuse Coil's authenticated image path and redirect credential checks.
            context.imageLoader.execute(ImageRequest.Builder(context).data(url).size(640, 360)
                .allowHardware(false).build())
        } as? SuccessResult ?: return
        val temp = File.createTempFile("artwork-", ".tmp", file.parentFile)
        try {
            temp.outputStream().use {
                check(result.drawable.toBitmap().compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it))
            }
            // Commit with provider mutations so an old download cannot resurrect cleared artwork.
            WatchNextAccess.run(context, session) {
                check(temp.renameTo(file)) { "Cannot save launcher artwork" }
            }.getOrThrow()
        } finally { temp.delete() }
    }

    fun clear(context: Context) {
        directory(context).listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }
}

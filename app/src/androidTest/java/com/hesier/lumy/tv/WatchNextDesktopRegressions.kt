package com.hesier.lumy.tv

import android.app.Instrumentation
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms
import com.hesier.lumy.LumyApp
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.PlaybackState
import com.hesier.lumy.data.SavedServer
import com.hesier.lumy.data.backup.BackupStore
import com.hesier.lumy.data.backup.RestoreOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Called only after WatchNextDesktopTest's empty-emulator account and card guards. */
internal class WatchNextDesktopRegressions(private val instrumentation: Instrumentation) {
    private val context get() = instrumentation.targetContext
    private val app get() = LumyApp.instance

    suspend fun verifyArtwork(fixture: WatchNextDesktopFixture) {
        check(app.currentEntry?.session == fixture.session) { "Expected guarded fixture account" }
        val uri = posterUri()
        check(uri.scheme == "content" && uri.authority == "${context.packageName}.watchnext.art")
        check(uri.toString().matches(Regex("content://[A-Za-z0-9._]+/art/[0-9a-f]{64}\\.jpg")))
        check(!uri.toString().contains("fixture-token")) { "Poster URI must not contain credentials" }
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        check(bytes.size > 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) { "Expected local JPEG" }
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "JPEG must decode" }
        bitmap.recycle()
        check(shell("id -u").trim().toInt() != Process.myUid()) { "Poster check must run under another UID" }
        check(shellImage(uri).contentEquals(bytes)) { "Shell UID could not read identical poster bytes" }
        check(fixture.authorizedImages.get() > 0) { "Protected poster must have been fetched with credentials" }
        verifyRejectedPaths(uri)
        for (mode in listOf("w", "rw", "wa", "rwt")) {
            check(runCatching { context.contentResolver.openFileDescriptor(uri, mode)!!.close() }.isFailure) {
                "Artwork provider accepted write mode $mode"
            }
        }
        WatchNextManager.clearAll(context)
        check(rowCount() == 0) { "Clearing artwork must also clear real provider cards" }
        check(runCatching { context.contentResolver.openInputStream(uri)!!.close() }.isFailure) {
            "Cleared card retained readable artwork"
        }
        check(shellImage(uri).isEmpty()) { "Cleared artwork remained readable by another UID" }
    }

    suspend fun verifyLateArtwork(fixture: WatchNextDesktopFixture) = coroutineScope {
        check(rowCount() == 0) { "Late artwork regression requires cleared fixture cards" }
        WatchNextManager.onPlaybackProgress(context, fixture.server, fixture.item, 120_000L,
            600_000L, null, PlaybackState.PAUSED)
        val knownUri = posterUri()
        check(context.contentResolver.openInputStream(knownUri)!!.use { it.read() } >= 0)
        fixture.imageVersion++
        val gate = fixture.holdNextImage()
        val stale = checkNotNull(WatchNextManager.captureSession(fixture.server))
        val pending = async(Dispatchers.IO) {
            WatchNextManager.onPlaybackProgress(context, fixture.server, fixture.item, 180_000L,
                600_000L, null, PlaybackState.PAUSED, stale)
        }
        try {
            check(withContext(Dispatchers.IO) { gate.started.await(4, TimeUnit.SECONDS) }) {
                "New image URL did not reach the gated authenticated HTTP request"
            }
            WatchNextManager.clearAll(context)
            gate.release.countDown()
            pending.await()
            check(!gate.timedOut) { "Image gate timed out before clear could release it" }
            check(rowCount() == 0) { "Late artwork download recreated a card after clear" }
            check(WatchNextArtwork.directory(context).listFiles().orEmpty().none { it.extension == "jpg" }) {
                "Late artwork download recreated an orphan JPEG after clear"
            }
            check(shellImage(knownUri).isEmpty()) { "Known cleared artwork remained readable" }
        } finally {
            gate.release.countDown()
            pending.cancelAndJoin()
        }
    }

    suspend fun verifyRestore(fixture: WatchNextDesktopFixture) {
        val original = checkNotNull(app.currentEntry)
        check(original.session == fixture.session) { "Expected guarded fixture account" }
        val backup = BackupStore(context)
        val snapshot = backup.inspect(backup.export())
        val other = SavedServer("${original.id}-restore-b", original.session.copy(userId = "restore-user-b"))
        val otherServer = object : MediaServer by fixture.server { override val session = other.session }
        val field = LumyApp::class.java.getDeclaredField("server").apply { isAccessible = true }
        try {
            app.store.add(other)
            app.store.currentId = other.id
            field.set(app, otherServer)
            WatchNextManager.onServerChanged(context)
            val stale = checkNotNull(WatchNextManager.captureSession(otherServer))
            publishNext(fixture, otherServer, stale)
            check(nextRowCount() == 1) { "Account B must have a real unplayed NEXT card" }
            backup.restore(snapshot, RestoreOptions(settings = false, servers = true))
            check(app.currentEntry?.id == original.id) { "Backup must first restore account A's currentId" }
            app.switchTo(checkNotNull(app.currentEntry).id, forceReset = true)
            awaitEmpty()
            publishNext(fixture, otherServer, stale)
            check(rowCount() == 0) { "Account B's pre-restore session republished after restore" }
            check(app.store.servers.none { it.id == other.id }) { "Restore retained account B" }
        } finally {
            app.store.remove(other.id)
            app.store.update(original)
            app.store.currentId = original.id
            field.set(app, fixture.server)
        }
    }

    private suspend fun publishNext(
        fixture: WatchNextDesktopFixture, server: MediaServer, session: WatchNextSession,
    ) {
        val previous = fixture.item.copy(id = "restore-previous-episode", type = MediaType.EPISODE)
        WatchNextManager.onPlaybackProgress(context, server, previous, 590_000L, 600_000L,
            fixture.item, PlaybackState.STOPPED, session)
    }

    private fun verifyRejectedPaths(uri: Uri) {
        val invalid = listOf("/art/../shared_prefs/settings.xml", "/art/%2e%2e%2fshared_prefs%2fsettings.xml",
            "/art/%2e%2e/${uri.lastPathSegment}", "/wrong/${uri.lastPathSegment}", "/art/not-a-hash.jpg")
        for (path in invalid) {
            val target = uri.buildUpon().encodedPath(path).build()
            check(runCatching { context.contentResolver.openInputStream(target)!!.close() }.isFailure) {
                "Artwork provider accepted invalid path $path"
            }
            check(shellImage(target).isEmpty()) { "Invalid artwork path readable by another UID: $path" }
        }
    }

    private fun posterUri(): Uri {
        val columns = arrayOf(WatchNextPrograms.COLUMN_POSTER_ART_URI)
        return context.contentResolver.query(WatchNextPrograms.CONTENT_URI, columns, null, null, null)!!.use {
            check(it.moveToFirst() && it.count == 1) { "Expected one published fixture card" }
            Uri.parse(checkNotNull(it.getString(0)))
        }
    }

    private fun rowCount(): Int = context.contentResolver.query(WatchNextPrograms.CONTENT_URI,
        arrayOf(WatchNextPrograms._ID), null, null, null)!!.use { it.count }

    private fun nextRowCount(): Int = context.contentResolver.query(WatchNextPrograms.CONTENT_URI,
        arrayOf(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE), null, null, null)!!.use {
        var count = 0
        while (it.moveToNext()) if (it.getInt(0) == WatchNextPrograms.WATCH_NEXT_TYPE_NEXT) count++
        count
    }

    private suspend fun awaitEmpty() {
        val deadline = SystemClock.uptimeMillis() + 6_000
        while (rowCount() != 0) {
            check(SystemClock.uptimeMillis() < deadline) { "Restore did not clear account B's cards" }
            delay(30)
        }
    }

    private fun shellImage(uri: Uri): ByteArray {
        val value = uri.toString()
        require(value.matches(Regex("[A-Za-z0-9:/._%+-]+"))) { "Unsafe generated artwork URI" }
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("content read --uri $value"),
        ).use { it.readBytes() }
        return if (bytes.size > 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) bytes else byteArrayOf()
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}

package com.hesier.lumy.tv

import android.app.Instrumentation
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms
import com.hesier.lumy.LumyApp
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.PlaybackState
import com.hesier.lumy.core.ServerKind

/** Exercises both routes using real downloads and published provider rows. */
internal class WatchNextArtworkRoutingTest(private val instrumentation: Instrumentation) {
    private val context get() = instrumentation.targetContext
    private val app get() = LumyApp.instance

    suspend fun runAll(fixture: WatchNextDesktopFixture) {
        val entry = checkNotNull(app.currentEntry)
        val field = LumyApp::class.java.getDeclaredField("server").apply { isAccessible = true }
        try {
            for (kind in listOf(ServerKind.JELLYFIN, ServerKind.EMBY, ServerKind.PLEX)) {
                val httpUrl = "${fixture.publicImageUrl}&kind=$kind"
                val http = server(fixture, kind, httpUrl)
                app.store.update(entry.copy(session = http.session))
                field.set(app, http)
                WatchNextManager.onServerChanged(context)
                val requests = fixture.publicImages.get()
                publish(http, fixture)
                val uri = posterUri()
                check(uri.scheme == "content" && uri.authority == "${context.packageName}.watchnext.art") {
                    "$kind HTTP artwork must be downloaded by Lumy"
                }
                check(fixture.publicImages.get() > requests) { "$kind HTTP image was not fetched" }
                val bitmap = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
                checkNotNull(bitmap) { "$kind cached poster must decode" }.recycle()
                verifyHttps(fixture, kind, field)
            }
        } finally {
            WatchNextManager.clearAll(context)
            app.store.update(entry)
            field.set(app, fixture.server)
            WatchNextManager.onServerChanged(context)
        }
    }

    private suspend fun verifyHttps(fixture: WatchNextDesktopFixture, kind: ServerKind, field: java.lang.reflect.Field) {
        val url = "https://example.invalid/poster.jpg?kind=$kind"
        val https = server(fixture, kind, url)
        field.set(app, https)
        WatchNextManager.onServerChanged(context)
        val requests = fixture.publicImages.get()
        publish(https, fixture)
        check(posterUri().toString() == url) { "$kind HTTPS artwork must remain a direct URL" }
        check(requests == fixture.publicImages.get())
        check(WatchNextArtwork.directory(context).listFiles().orEmpty().isEmpty()) {
            "$kind HTTPS artwork must not create a local image"
        }
    }

    private fun server(fixture: WatchNextDesktopFixture, kind: ServerKind, url: String): MediaServer =
        object : MediaServer by fixture.server {
            override val session = fixture.session.copy(kind = kind)
            override fun imageUrl(ref: String, width: Int, height: Int) = url
        }

    private suspend fun publish(server: MediaServer, fixture: WatchNextDesktopFixture) {
        WatchNextManager.onPlaybackProgress(context, server, fixture.item, 120_000L,
            600_000L, null, PlaybackState.PAUSED)
    }

    private fun posterUri(): Uri = context.contentResolver.query(WatchNextPrograms.CONTENT_URI,
        arrayOf(WatchNextPrograms.COLUMN_POSTER_ART_URI), null, null, null)!!.use {
        check(it.count == 1 && it.moveToFirst()) { "Expected a published artwork fixture" }
        Uri.parse(checkNotNull(it.getString(0)))
    }
}

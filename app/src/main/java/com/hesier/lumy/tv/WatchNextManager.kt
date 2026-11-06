package com.hesier.lumy.tv

import android.content.Context
import com.hesier.lumy.LumyApp
import com.hesier.lumy.core.HttpStatusException
import com.hesier.lumy.core.HubLabel
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.PlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Publishes the selected account's resume and next-episode cards to the TV launcher. */
object WatchNextManager {
    private const val MAX_PROGRAMS = 5
    private const val MIN_POSITION_MS = 10_000L
    private const val COMPLETION_RATIO = 0.90
    private const val COMPLETION_REMAINING_MS = 30_000L

    fun isSupported(): Boolean = WatchNextSupport.isSupported(LumyApp.instance)
    suspend fun isAvailable(context: Context): Boolean = WatchNextSupport.ensure(context)
    fun captureSession(server: MediaServer): WatchNextSession? = WatchNextAccess.capture(server)

    fun onServerChanged(context: Context) {
        WatchNextAccess.invalidate()
        if (WatchNextSupport.isUnavailable(context)) return
        LumyApp.instance.appScope.launch { WatchNextAccess.run(context) {} }
    }

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        if (!isAvailable(context)) return
        LumyApp.instance.settings.watchNextEnabled = enabled
        WatchNextAccess.invalidate()
        WatchNextAccess.run(context) {}.getOrThrow()
        if (enabled && LumyApp.instance.currentEntry != null) refresh(context)
    }

    suspend fun refresh(context: Context) {
        if (!LumyApp.instance.settings.watchNextEnabled || !isAvailable(context)) return
        val server = LumyApp.instance.currentServer()
            ?: error(context.getString(com.hesier.lumy.R.string.no_server))
        val session = captureSession(server) ?: return
        val items = server.homeHubs().filter { it.label is HubLabel.ContinueWatching }.flatMap { it.items }
        sync(context, server, items, session).getOrThrow()
    }

    suspend fun onPlaybackProgress(
        context: Context, server: MediaServer, item: MediaItem, positionMs: Long, durationMs: Long,
        nextEpisode: MediaItem?, state: PlaybackState,
        session: WatchNextSession? = captureSession(server), resume: WatchNextResume? = null,
    ) {
        if (session == null || !isAvailable(context)) return
        var needsArtwork = false
        val ready = WatchNextAccess.run(context, session) {
            needsArtwork = if (isFinished(positionMs, durationMs)) nextEpisode != null
                else durationMs > 0 && (positionMs >= MIN_POSITION_MS ||
                    WatchNextProgramsStore(context).records().any { it.mediaId == item.id })
        }
        if (ready.isFailure || !WatchNextAccess.isCurrent(session) || WatchNextSupport.isUnavailable(context)) return
        val target = if (isFinished(positionMs, durationMs)) nextEpisode else item
        val artwork = if (needsArtwork) target?.let { WatchNextArtwork.prepare(context, server, it, session) } else null
        WatchNextAccess.run(context, session) {
            val store = WatchNextProgramsStore(context)
            if (!accept(session, item)) return@run
            if (isFinished(positionMs, durationMs)) {
                store.records().filter { it.mediaId == item.id }.forEach(store::remove)
                if (nextEpisode != null && accept(session, nextEpisode)) {
                    publish(store, session, WatchNextPublication(nextEpisode, 0,
                        nextEpisode.durationMs ?: 0, next = true,
                        resume = WatchNextResume(playlistId = resume?.playlistId), artworkUri = artwork))
                }
            } else if (durationMs > 0 && (positionMs >= MIN_POSITION_MS || store.records().any { it.mediaId == item.id })) {
                publish(store, session, WatchNextPublication(item, positionMs.coerceAtLeast(0), durationMs,
                    resume = resume, artworkUri = artwork))
            }
        }
    }

    suspend fun syncContinueWatching(
        context: Context, server: MediaServer, items: List<MediaItem>,
        session: WatchNextSession? = captureSession(server),
    ) {
        if (session == null || !isAvailable(context)) return
        sync(context, server, items, session)
    }

    private suspend fun sync(
        context: Context, server: MediaServer, items: List<MediaItem>, session: WatchNextSession,
    ): Result<Unit> {
        val store = WatchNextProgramsStore(context)
        var nextRecords = emptyList<WatchNextRecord>()
        val read = WatchNextAccess.run(context, session) { nextRecords = store.records().filter { it.next } }
        if (read.isFailure) return read
        // Validate outside the provider lock; a transient server error must retain the card.
        val obsoleteNext = nextRecords.mapNotNull { record ->
            try {
                val item = server.detail(record.mediaId).item
                record.mediaId.takeIf { item.watched || isFinished(item.viewOffsetMs ?: 0, item.durationMs ?: 0) }
            } catch (e: CancellationException) { throw e }
            catch (e: HttpStatusException) { record.mediaId.takeIf { e.code == 404 } }
            catch (_: Exception) { null }
        }.toSet()
        val active = items.filter { eligible(it) }.distinctBy { seriesKey(it) }.take(MAX_PROGRAMS)
        val artwork = active.associate { it.id to WatchNextArtwork.prepare(context, server, it, session) }
        return WatchNextAccess.run(context, session) {
            if (!WatchNextAccess.acceptSnapshot(session)) return@run
            val ids = active.map { it.id }.toSet()
            store.records().filter { it.mediaId !in ids && (!it.next || it.mediaId in obsoleteNext) }
                .filter { accept(session, it) }.forEach(store::remove)
            active.filter { accept(session, it) }.forEach { item ->
                publish(store, session, WatchNextPublication(item, item.viewOffsetMs ?: 0, item.durationMs ?: 0, artworkUri = artwork[item.id]))
            }
        }
    }

    suspend fun removeItem(context: Context, mediaId: String, session: WatchNextSession?) =
        removeWatched(context, MediaItem(mediaId, MediaType.MOVIE, ""), session)

    suspend fun removeWatched(context: Context, item: MediaItem, session: WatchNextSession?) {
        if (session == null || !isAvailable(context)) return
        WatchNextAccess.run(context, session) {
            if (!accept(session, item)) return@run
            val store = WatchNextProgramsStore(context)
            store.records().filter { it.mediaId == item.id ||
                (item.type == MediaType.SHOW && it.showId == item.id) ||
                (item.type == MediaType.SEASON && it.seasonId == item.id) }
                .filter { accept(session, it) }.forEach(store::remove)
        }
    }

    suspend fun clearAll(context: Context) {
        if (!isAvailable(context)) return
        WatchNextAccess.invalidate()
        WatchNextAccess.run(context) {}.getOrThrow()
    }

    internal fun clearInternal(context: Context) {
        if (WatchNextSupport.hasApi()) WatchNextProgramsStore(context).clear()
    }

    private fun publish(
        store: WatchNextProgramsStore, session: WatchNextSession, publication: WatchNextPublication,
    ) {
        val item = publication.item
        if (item.type == MediaType.EPISODE && item.grandparentId != null) {
            store.records().filter { it.mediaId != item.id && it.showId == item.grandparentId }
                .filter { accept(session, it) }.forEach(store::remove)
        }
        store.publish(session, publication)
    }

    private fun isFinished(positionMs: Long, durationMs: Long): Boolean = durationMs > 0 &&
        (positionMs >= durationMs * COMPLETION_RATIO || durationMs - positionMs <= COMPLETION_REMAINING_MS)

    private fun eligible(item: MediaItem): Boolean = !item.watched &&
        (item.durationMs ?: 0) > 0 && (item.viewOffsetMs ?: 0) >= MIN_POSITION_MS &&
        !isFinished(item.viewOffsetMs ?: 0, item.durationMs ?: 0)

    private fun seriesKey(item: MediaItem) =
        if (item.type == MediaType.EPISODE) item.grandparentId?.let { "show:$it" } ?: "item:${item.id}" else "item:${item.id}"

    private fun accept(session: WatchNextSession, item: MediaItem): Boolean {
        val keys = mutableListOf("item:${item.id}")
        when (item.type) {
            MediaType.SHOW -> keys += "show:${item.id}"
            MediaType.SEASON -> keys += "season:${item.id}"
            MediaType.EPISODE -> {
                item.grandparentId?.let { keys += "show:$it" }
                item.parentId?.let { keys += "season:$it" }
            }
            else -> Unit
        }
        return WatchNextAccess.accept(session, keys)
    }

    private fun accept(session: WatchNextSession, record: WatchNextRecord): Boolean =
        WatchNextAccess.accept(session, listOfNotNull("item:${record.mediaId}",
            record.showId?.let { "show:$it" }, record.seasonId?.let { "season:$it" }))
}

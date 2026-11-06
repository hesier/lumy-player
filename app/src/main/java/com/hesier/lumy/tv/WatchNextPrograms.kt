package com.hesier.lumy.tv

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms
import androidx.tvprovider.media.tv.WatchNextProgram
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.ui.player.PlayerActivity
import org.json.JSONObject

/** Stable media version IDs survive changes to the server's version ordering. */
data class WatchNextResume(val versionId: String? = null, val playlistId: String? = null)

internal data class WatchNextRecord(
    val id: Long, val mediaId: String, val showId: String?, val seasonId: String?,
    val next: Boolean, val resume: WatchNextResume, val artworkUri: Uri?,
)

internal data class WatchNextPublication(
    val item: MediaItem, val positionMs: Long, val durationMs: Long,
    val next: Boolean = false, val resume: WatchNextResume? = null, val artworkUri: Uri? = null,
)

internal class WatchNextProgramsStore(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val prefs get() = context.getSharedPreferences("watch_next_programs", Context.MODE_PRIVATE)

    fun records(): List<WatchNextRecord> {
        val columns = arrayOf(WatchNextPrograms._ID, WatchNextPrograms.COLUMN_CONTENT_ID,
            WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_DATA,
            WatchNextPrograms.COLUMN_POSTER_ART_URI)
        val cursor = resolver.query(WatchNextPrograms.CONTENT_URI, columns, null, null, null)
            ?: throw WatchNextUnavailableException()
        return cursor.use {
            buildList {
                while (cursor.moveToNext()) {
                    val data = if (cursor.isNull(3)) JSONObject() else runCatching {
                        JSONObject(String(cursor.getBlob(3), Charsets.UTF_8))
                    }.getOrDefault(JSONObject())
                    add(WatchNextRecord(cursor.getLong(0), cursor.getString(1).orEmpty(),
                        data.optional("show"), data.optional("season"),
                        cursor.getInt(2) == WatchNextPrograms.WATCH_NEXT_TYPE_NEXT,
                        WatchNextResume(data.optional("version"), data.optional("playlist")),
                        cursor.getString(4)?.let(Uri::parse)))
                }
            }
        }
    }

    fun remove(record: WatchNextRecord) {
        resolver.delete(TvContractCompat.buildWatchNextProgramUri(record.id), null, null)
        if (prefs.getLong(record.mediaId, -1L) == record.id) prefs.edit { remove(record.mediaId) }
    }

    fun clear() {
        val ids = records().map { it.id }.toSet() + prefs.all.values.mapNotNull { (it as? Number)?.toLong() }
        for (id in ids) resolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null)
        prefs.edit { clear() }
        WatchNextArtwork.clear(context)
    }

    fun publish(session: WatchNextSession, publication: WatchNextPublication) {
        val old = records().firstOrNull { it.mediaId == publication.item.id }
        val resume = publication.resume ?: old?.resume ?: WatchNextResume()
        val program = buildProgram(session.serverId,
            publication.copy(artworkUri = publication.artworkUri ?: old?.artworkUri), resume)
        val id = old?.id ?: prefs.getLong(publication.item.id, -1L)
        if (id != -1L && resolver.update(TvContractCompat.buildWatchNextProgramUri(id), program.toContentValues(), null, null) > 0) return
        val uri = resolver.insert(WatchNextPrograms.CONTENT_URI, program.toContentValues()) ?: throw WatchNextUnavailableException()
        prefs.edit { putLong(publication.item.id, ContentUris.parseId(uri)) }
    }

    private fun buildProgram(
        serverId: String, publication: WatchNextPublication, resume: WatchNextResume,
    ): WatchNextProgram {
        val item = publication.item
        val intent = PlayerActivity.intent(context, item.id, 0, publication.positionMs, resume.playlistId, serverId)
            .putExtra("watch_next_version_id", resume.versionId).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val data = JSONObject().putOpt("show", item.grandparentId).putOpt("season", item.parentId)
            .putOpt("version", resume.versionId).putOpt("playlist", resume.playlistId)
        val builder = WatchNextProgram.Builder()
            .setType(when (item.type) {
                MediaType.MOVIE -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
                MediaType.EPISODE -> TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
                else -> TvContractCompat.PreviewPrograms.TYPE_CLIP
            })
            .setWatchNextType(if (publication.next) WatchNextPrograms.WATCH_NEXT_TYPE_NEXT else WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setContentId(item.id)
            .setInternalProviderData(data.toString().toByteArray(Charsets.UTF_8))
            .setTitle(if (item.type == MediaType.EPISODE) item.showTitle?.takeIf { it.isNotBlank() } ?: item.title else item.title)
            .setDescription(item.summary.orEmpty())
            .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
            .setLastPlaybackPositionMillis(publication.positionMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            .setDurationMillis(publication.durationMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            .setIntent(intent)
        publication.artworkUri?.let { uri ->
            builder.setPosterArtUri(uri)
                .setPosterArtAspectRatio(if (item.thumb != null || item.backdrop != null)
                    TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9 else TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
        }
        if (item.type == MediaType.EPISODE) {
            item.seasonNumber?.let { builder.setSeasonNumber(it) }
            item.episodeNumber?.let { builder.setEpisodeNumber(it) }
            builder.setEpisodeTitle(item.title)
        }
        return builder.build()
    }

    private fun JSONObject.optional(key: String): String? = optString(key).takeIf { it.isNotBlank() }
}

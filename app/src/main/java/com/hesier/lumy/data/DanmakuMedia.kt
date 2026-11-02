package com.hesier.lumy.data

import com.hesier.lumy.core.MediaDetail
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.ServerDanmaku
import com.hesier.lumy.net.danmaku.DanmakuAnime
import com.hesier.lumy.net.danmaku.DanmakuComment
import com.hesier.lumy.net.danmaku.DanmakuEpisode
import com.hesier.lumy.net.danmaku.DanmakuFailure
import java.security.MessageDigest

internal enum class DanmakuSourceKind { SERVER, OFFICIAL, CUSTOM }
internal data class DanmakuChoice(val kind: DanmakuSourceKind, val identity: String)
internal data class DanmakuMatch(val choice: DanmakuChoice, val anime: DanmakuAnime, val episode: DanmakuEpisode,
    val year: Int?, val season: Int?, val number: Int?)

internal data class DanmakuMedia(
    val key: String, val serverKey: String, val title: String, val originalTitle: String?,
    val type: MediaType, val year: Int?, val season: Int?, val number: Int?, val plugin: ServerDanmaku?, val versionId: String = "",
) {
    companion object {
        fun from(server: MediaServer, detail: MediaDetail, version: MediaVersion, entryId: String): DanmakuMedia {
            val item = detail.item
            val identity = "$entryId|${server.session.kind}|${server.session.serverId}|${server.session.userId}"
            return DanmakuMedia(danmakuHash("$identity|${item.id}"), danmakuHash(identity),
                item.showTitle ?: item.title, detail.originalTitle.takeIf { item.type == MediaType.MOVIE },
                item.type, item.year, item.seasonNumber, item.episodeNumber, server.danmakuResource(item.id), version.id)
        }
    }
}

internal sealed interface DanmakuLoad {
    data class Loaded(val comments: List<DanmakuComment>, val source: DanmakuChoice,
        val match: DanmakuMatch? = null, val automatic: Boolean = false) : DanmakuLoad
    data class NeedsMatch(val source: DanmakuChoice) : DanmakuLoad
    data class Empty(val source: DanmakuChoice) : DanmakuLoad
    data class Failed(val reason: DanmakuFailure, val manual: Boolean = false) : DanmakuLoad
}

internal fun danmakuHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

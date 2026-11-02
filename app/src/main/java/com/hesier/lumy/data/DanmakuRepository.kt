package com.hesier.lumy.data

import com.hesier.lumy.core.ServerDanmakuFormat
import com.hesier.lumy.net.danmaku.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Source priority and stored corrections are independent of user display filters. */
internal class DanmakuRepository(private val services: DanmakuServices, private val store: DanmakuStore,
    private val http: okhttp3.OkHttpClient = DanmakuServices.http) {
    var serverFailure: DanmakuFailure? = null
        private set

    suspend fun load(media: DanmakuMedia, force: Boolean = false): DanmakuLoad = withContext(Dispatchers.IO) {
        try {
            serverFailure = null
            val manual = store.choice(media.key)
            if (manual != null) return@withContext pinned(media, manual, force)
            if (media.plugin != null) {
                try {
                    val plugin = plugin(media, force)
                    if (plugin is DanmakuLoad.Loaded) return@withContext plugin
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: DanmakuException) { serverFailure = error.reason }
            }
            online(media, services.endpoint, force)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: DanmakuException) { DanmakuLoad.Failed(error.reason, store.choice(media.key) != null) }
        catch (_: Exception) { DanmakuLoad.Failed(DanmakuFailure.CONNECTION) }
    }

    private suspend fun pinned(media: DanmakuMedia, choice: DanmakuChoice, force: Boolean): DanmakuLoad {
        if (choice.kind == DanmakuSourceKind.SERVER) return plugin(media, force)
        val endpoint = endpoint(choice)
        return online(media, endpoint, force)
    }

    fun endpoint(choice: DanmakuChoice): DanmakuEndpoint {
        val endpoint = when (choice.kind) {
            DanmakuSourceKind.OFFICIAL -> services.official()
            DanmakuSourceKind.CUSTOM -> services.custom()
            else -> throw DanmakuException(DanmakuFailure.UNAVAILABLE)
        }
        if (endpoint.identity != choice.identity) throw DanmakuException(DanmakuFailure.UNAVAILABLE)
        return endpoint
    }

    suspend fun search(choice: DanmakuChoice, keyword: String): List<DanmakuAnime> =
        DanmakuApi(http, endpoint(choice)).search(keyword)

    suspend fun details(choice: DanmakuChoice, anime: DanmakuAnime): DanmakuAnime =
        DanmakuApi(http, endpoint(choice)).details(anime.id)

    private suspend fun plugin(media: DanmakuMedia, force: Boolean): DanmakuLoad {
        val resource = media.plugin ?: throw DanmakuException(DanmakuFailure.NOT_FOUND)
        val choice = DanmakuChoice(DanmakuSourceKind.SERVER, "server:${media.serverKey}")
        val key = "${choice.identity}:${media.key}:${media.versionId}:${danmakuHash(resource.url)}"
        if (force) store.invalidate(key)
        val comments = store.cached(key) ?: run {
            val bytes = DanmakuTransport(http).get(resource.url, resource.headers)
            val format = if (resource.format == ServerDanmakuFormat.XML) DanmakuFormat.XML else DanmakuFormat.EMBY_JSON
            DanmakuParser.parse(bytes, format).also {
                pluginAvailable[media.serverKey] = true
                if (it.isNotEmpty()) store.cache(key, it)
            }
        }
        return if (comments.isEmpty()) DanmakuLoad.Empty(choice) else DanmakuLoad.Loaded(comments, choice)
    }

    private suspend fun online(media: DanmakuMedia, endpoint: DanmakuEndpoint, force: Boolean): DanmakuLoad {
        val choice = DanmakuChoice(if (endpoint.official) DanmakuSourceKind.OFFICIAL else DanmakuSourceKind.CUSTOM, endpoint.identity)
        val api = DanmakuApi(http, endpoint)
        val saved = store.match(media.key, choice.identity)
        val selected = if (saved != null) resolveSaved(api, saved) else {
            val anime = DanmakuMatcher.anime(media, api.search(media.title))
                ?: media.originalTitle?.takeIf { !DanmakuMatcher.same(it, media.title) }?.let {
                    DanmakuMatcher.anime(media, api.search(it))
                } ?: return DanmakuLoad.NeedsMatch(choice)
            val details = api.details(anime.id)
            if (!compatibleAnime(details, anime)) return DanmakuLoad.NeedsMatch(choice)
            val episode = DanmakuMatcher.episode(media, details) ?: return DanmakuLoad.NeedsMatch(choice)
            DanmakuMatch(choice, details, episode, media.year, media.season, media.number)
        }
        if (selected == null) return DanmakuLoad.NeedsMatch(choice)
        if (saved != null && (saved.anime.id != selected.anime.id || saved.episode.id != selected.episode.id))
            store.remember(media.key, selected)
        val key = "${choice.identity}:${selected.episode.id}:" + danmakuHash(
            "${selected.anime.id}|${selected.anime.title}|${selected.anime.year}|${selected.episode.title}|${selected.episode.number}|${selected.episode.seasonId}")
        if (force) store.invalidate(key)
        val comments = store.cached(key) ?: api.comments(selected.episode.id).also { if (it.isNotEmpty()) store.cache(key, it) }
        return if (comments.isEmpty()) DanmakuLoad.Empty(choice) else DanmakuLoad.Loaded(comments, choice)
    }

    private suspend fun resolveSaved(api: DanmakuApi, saved: DanmakuMatch): DanmakuMatch? {
        val details = try { api.details(saved.anime.id) }
        catch (error: DanmakuException) {
            if (error.reason != DanmakuFailure.NOT_FOUND) throw error
            null
        }
        if (details != null && compatibleAnime(details, saved.anime)) {
            val episode = details.episodes.singleOrNull { it.id == saved.episode.id && compatibleEpisode(it, saved.episode) }
            if (episode != null) return saved.copy(anime = details, episode = episode)
        }
        val candidate = api.search(saved.anime.title).filter { compatibleAnime(it, saved.anime) }.singleOrNull() ?: return null
        val refreshed = api.details(candidate.id)
        if (!compatibleAnime(refreshed, saved.anime)) return null
        val episode = refreshed.episodes.filter { compatibleEpisode(it, saved.episode) }.singleOrNull() ?: return null
        return saved.copy(anime = refreshed, episode = episode)
    }

    private fun compatibleAnime(current: DanmakuAnime, saved: DanmakuAnime) =
        DanmakuMatcher.same(current.title, saved.title) &&
            (saved.year == null || current.year == saved.year) &&
            (saved.type == null || current.type == saved.type)

    private fun compatibleEpisode(current: DanmakuEpisode, saved: DanmakuEpisode) =
        DanmakuMatcher.same(current.title, saved.title) && current.number == saved.number &&
            current.seasonId == saved.seasonId

    companion object {
        // A missing sidecar (404) never becomes a server-wide negative capability entry.
        private val pluginAvailable = ConcurrentHashMap<String, Boolean>()
    }
}

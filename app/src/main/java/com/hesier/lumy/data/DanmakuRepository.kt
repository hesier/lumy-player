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
        val configured = runCatching { services.endpoint }.getOrNull()
        val manual = configured != null && store.match(media.key, configured.identity) != null
        try {
            serverFailure = null
            // Explicit per-item choices remain active until the user restores automatic matching.
            if (manual) return@withContext online(media, checkNotNull(configured), force)
            if (media.plugin != null) {
                try {
                    val plugin = plugin(media, force)
                    if (plugin is DanmakuLoad.Loaded) return@withContext plugin
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: DanmakuException) { serverFailure = error.reason }
            }
            online(media, configured ?: services.endpoint, force)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: DanmakuException) { DanmakuLoad.Failed(error.reason, manual) }
        catch (_: Exception) { DanmakuLoad.Failed(DanmakuFailure.CONNECTION, manual) }
    }

    fun onlineChoice(): DanmakuChoice {
        val endpoint = services.endpoint
        return DanmakuChoice(if (endpoint.official) DanmakuSourceKind.OFFICIAL else DanmakuSourceKind.CUSTOM, endpoint.identity)
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
            val decision = DanmakuAutoMatch(api).run(media)
            val suggestion = decision.selected ?: return DanmakuLoad.NeedsMatch(choice, decision.reason, decision.suggestions)
            DanmakuMatch(choice, suggestion.anime, checkNotNull(suggestion.episode), media.year, media.season,
                media.number, suggestion.sourceOffsetMs)
        }
        if (selected == null) return DanmakuLoad.NeedsMatch(choice, DanmakuMatchReason.METADATA)
        if (saved != null && (saved.anime.id != selected.anime.id || saved.episode.id != selected.episode.id))
            store.remember(media.key, selected)
        val key = "${choice.identity}:${selected.episode.id}:" + danmakuHash(
            "${selected.anime.id}|${selected.anime.title}|${selected.anime.year}|${selected.episode.title}|${selected.episode.number}|${selected.episode.seasonId}")
        if (force) store.invalidate(key)
        val comments = store.cached(key) ?: api.comments(selected.episode.id).also { if (it.isNotEmpty()) store.cache(key, it) }
        return if (comments.isEmpty()) DanmakuLoad.Empty(choice)
            else DanmakuLoad.Loaded(comments, choice, selected, automatic = saved == null, sourceOffsetMs = selected.sourceOffsetMs)
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
        val candidates = api.search(saved.anime.title).filter { compatibleAnime(it, saved.anime) }
        if (candidates.size > MAX_RECOVERY_CANDIDATES) return null
        val recovered = candidates.mapNotNull { candidate ->
            val refreshed = try { api.details(candidate.id) }
            catch (error: DanmakuException) {
                if (error.reason != DanmakuFailure.NOT_FOUND) throw error
                return@mapNotNull null
            }
            if (!compatibleAnime(refreshed, saved.anime)) return@mapNotNull null
            val episode = refreshed.episodes.filter { compatibleEpisode(it, saved.episode) }.distinct().singleOrNull()
                ?: return@mapNotNull null
            saved.copy(anime = refreshed, episode = episode)
        }.distinctBy { it.anime.id to it.episode.id }
        return recovered.singleOrNull()
    }

    private fun compatibleAnime(current: DanmakuAnime, saved: DanmakuAnime) =
        DanmakuMatcher.same(current.title, saved.title) &&
            (saved.year == null || current.year == saved.year) &&
            (saved.type == null || current.type == saved.type)

    private fun compatibleEpisode(current: DanmakuEpisode, saved: DanmakuEpisode) =
        DanmakuMatcher.same(current.title, saved.title) && current.number == saved.number &&
            current.seasonId == saved.seasonId

    companion object {
        private const val MAX_RECOVERY_CANDIDATES = 6
        // A missing sidecar (404) never becomes a server-wide negative capability entry.
        private val pluginAvailable = ConcurrentHashMap<String, Boolean>()
    }
}

package com.hesier.lumy.ui.player

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.hesier.lumy.R
import com.hesier.lumy.core.MediaDetail
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.data.*
import com.hesier.lumy.net.danmaku.*
import com.hesier.lumy.ui.ThemedActivity
import com.hesier.lumy.ui.settings.danmakuErrorText
import com.hesier.lumy.ui.widget.Option
import com.hesier.lumy.ui.widget.OptionsPanel
import com.hesier.lumy.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** Loading stays independent of video startup; one generation owns each visible result. */
internal class PlayerDanmaku(
    private val activity: ThemedActivity,
    private val host: PlayerView,
    player: Player,
    positionMs: () -> Long,
    private val preferences: SharedPreferences = activity.getSharedPreferences("settings", Context.MODE_PRIVATE),
    private val store: DanmakuStore = DanmakuStore(activity),
    private val onMasterChanged: () -> Unit = {},
) {
    private val settings = DanmakuSettings(preferences)
    private val sources = DanmakuSourceSettings(preferences)
    private val services = DanmakuServices(activity, sources)
    private val repository = DanmakuRepository(services, store)
    private val overlay = DanmakuOverlay(host, player, positionMs, preferences)
    private var media: DanmakuMedia? = null
    private var job: Job? = null
    private var actionJob: Job? = null
    private var generation = 0
    private var testing = false
    private var released = false
    private var result: DanmakuLoad? = null
    private var loading = false
    private var lastNotice: String? = null
    private enum class Feedback { AUTOMATIC, REFRESH, MATCH }
    private val enabled: Boolean get() = settings.enabled && media?.let { store.mediaEnabled(it.key) } == true
    private var lastMasterEnabled = settings.enabled
    private val refresh = Runnable {
        if (lastMasterEnabled != settings.enabled) {
            lastMasterEnabled = settings.enabled
            onMasterChanged()
        }
        reload()
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "danmaku_enabled" || key in DanmakuSourceSettings.KEYS) {
            host.removeCallbacks(refresh); host.post(refresh)
        }
    }
    private val actions = DanmakuActions(activity, repository, ::current, { generation }, ::selected, ::runAction)

    init { preferences.registerOnSharedPreferenceChangeListener(listener) }

    fun bind(server: MediaServer, detail: MediaDetail, version: MediaVersion, entryId: String) {
        clear()
        testing = false
        media = DanmakuMedia.from(server, detail, version, entryId)
        reload()
    }

    fun setTestActive(value: Boolean) {
        testing = value
        reload()
    }

    fun showMenu() {
        if (!settings.enabled || released) return
        val request = media ?: return
        val revision = generation
        val entries = buildList<Pair<Option, () -> Unit>> {
            add(Option(activity.getString(R.string.danmaku_media_enabled), activity.getString(if (enabled) R.string.on else R.string.off)) to {
                media?.let { store.setMediaEnabled(it.key, !store.mediaEnabled(it.key)) }
                reload()
            })
            if (enabled) {
                add(Option(activity.getString(R.string.danmaku_manual_match),
                    (result as? DanmakuLoad.Loaded)?.match?.let(::matchLabel)) to { actions.open(result as? DanmakuLoad.NeedsMatch) })
                add(Option(activity.getString(R.string.danmaku_reload), activity.getString(R.string.danmaku_reload_detail)) to {
                    reload(force = true, feedback = Feedback.REFRESH)
                })
                add(Option(activity.getString(R.string.danmaku_restore_auto),
                    activity.getString(R.string.danmaku_restore_auto_detail)) to ::restore)
            }
        }
        OptionsPanel(activity, status(), entries.map { it.first }) {
            if (settings.enabled && !released && media?.key == request.key && generation == revision)
                entries[it].second()
        }.show()
    }

    private fun reload(force: Boolean = false, feedback: Feedback = Feedback.AUTOMATIC, resetMatch: Boolean = false) {
        generation++
        actions.invalidate()
        job?.cancel()
        actionJob?.cancel()
        loading = false
        result = null
        overlay.clear()
        val request = media ?: return
        if (!enabled || testing || released) { lastNotice = null; return }
        val version = generation
        loading = true
        if (feedback != Feedback.AUTOMATIC) toast(activity.getString(R.string.danmaku_loading))
        job = activity.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                if (resetMatch) store.restoreAuto(request.key)
                repository.load(request, force)
            }
            if (version != generation || released || !enabled) return@launch
            loading = false
            result = loaded
            if (loaded is DanmakuLoad.Loaded) overlay.setComments(loaded.comments, loaded.sourceOffsetMs)
            notifyResult(loaded, feedback)
            repository.serverFailure?.let { DebugLog.i("LumyDanmaku", "server plugin failed: $it") }
        }
    }

    private fun current(): Pair<DanmakuMedia, DanmakuChoice>? {
        if (!enabled || testing || released) return null
        val item = media ?: return null
        val source = when (val value = result) {
            is DanmakuLoad.Loaded -> value.source
            is DanmakuLoad.NeedsMatch -> value.source
            is DanmakuLoad.Empty -> value.source
            else -> when (sources.onlineSource) {
                DanmakuOnlineSource.OFFICIAL -> DanmakuChoice(DanmakuSourceKind.OFFICIAL, "official:dandanplay")
                DanmakuOnlineSource.CUSTOM -> runCatching { DanmakuChoice(DanmakuSourceKind.CUSTOM, services.custom().identity) }
                    .getOrElse { DanmakuChoice(DanmakuSourceKind.CUSTOM, "unconfigured") }
            }
        }
        return item to source
    }

    private fun selected(anime: DanmakuAnime, episode: DanmakuEpisode, choice: DanmakuChoice, sourceOffsetMs: Long) {
        val request = media ?: return
        store.remember(request.key, DanmakuMatch(choice, anime, episode, request.year, request.season, request.number, sourceOffsetMs))
        reload(feedback = Feedback.MATCH)
    }

    private fun restore() {
        reload(force = true, feedback = Feedback.MATCH, resetMatch = true)
    }

    private fun matchLabel(match: DanmakuMatch): String = listOf(match.anime.title, match.episode.title)
        .filter { it.isNotBlank() }.distinct().joinToString(" · ")

    private fun notifyResult(loaded: DanmakuLoad, feedback: Feedback) {
        val match = (loaded as? DanmakuLoad.Loaded)?.match
        val message = if (loaded is DanmakuLoad.Loaded && match != null && feedback != Feedback.REFRESH) {
            activity.getString(if (loaded.automatic) R.string.danmaku_auto_matched else R.string.danmaku_match_loaded,
                matchLabel(match))
        } else status()
        val key = "${media?.key}|$message"
        if (feedback != Feedback.AUTOMATIC || lastNotice != key) toast(message)
        lastNotice = key
    }

    private fun runAction(block: suspend () -> Unit) {
        if (!enabled || released || testing) return
        actionJob?.cancel()
        val token = generation
        toast(activity.getString(R.string.danmaku_loading))
        actionJob = activity.lifecycleScope.launch {
            try { if (token == generation) block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: DanmakuException) {
                if (token == generation && !released) toast(danmakuErrorText(activity, error.reason))
            }
            catch (_: Exception) {
                if (token == generation && !released) toast(activity.getString(R.string.danmaku_status_error))
            }
        }
    }

    private fun status(): String = when {
        !enabled -> activity.getString(R.string.off)
        loading -> activity.getString(R.string.danmaku_loading)
        else -> when (val value = result) {
            is DanmakuLoad.Loaded -> "${sourceLabel(value.source)} · ${activity.getString(R.string.danmaku_loaded, value.comments.size)}"
            is DanmakuLoad.NeedsMatch -> activity.getString(reasonText(value.reason))
            is DanmakuLoad.Empty -> activity.getString(R.string.danmaku_empty)
            is DanmakuLoad.Failed -> if (value.manual) activity.getString(R.string.danmaku_manual_failed) + " · " + danmakuErrorText(activity, value.reason)
                else danmakuErrorText(activity, value.reason)
            null -> activity.getString(R.string.danmaku_empty)
        }
    }

    private fun sourceLabel(value: DanmakuChoice) = activity.getString(when (value.kind) {
        DanmakuSourceKind.SERVER -> R.string.danmaku_source_server
        DanmakuSourceKind.OFFICIAL -> R.string.danmaku_source_official
        DanmakuSourceKind.CUSTOM -> R.string.danmaku_source_custom
    })
    private fun toast(value: String) = Toast.makeText(activity, value, Toast.LENGTH_LONG).show()

    fun clear() {
        generation++; actions.invalidate(); job?.cancel(); actionJob?.cancel(); result = null; media = null; loading = false
        overlay.clear()
    }

    fun release() {
        if (released) return
        released = true
        clear()
        host.removeCallbacks(refresh)
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
        overlay.release()
    }
}

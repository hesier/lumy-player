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
import kotlinx.coroutines.launch

/** Loading stays independent of video startup; one generation owns each visible result. */
internal class PlayerDanmaku(
    private val activity: ThemedActivity,
    private val host: PlayerView,
    player: Player,
    positionMs: () -> Long,
) {
    private val preferences = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val settings = DanmakuSettings(preferences)
    private val sources = DanmakuSourceSettings(preferences)
    private val services = DanmakuServices(activity)
    private val store = DanmakuStore(activity)
    private val repository = DanmakuRepository(services, store)
    private val overlay = DanmakuOverlay(host, player, positionMs)
    private var media: DanmakuMedia? = null
    private var job: Job? = null
    private var actionJob: Job? = null
    private var generation = 0
    private var testing = false
    private var released = false
    private var result: DanmakuLoad? = null
    private var loading = false
    private val refresh = Runnable { reload() }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "danmaku_enabled" || key in DanmakuSourceSettings.KEYS) {
            host.removeCallbacks(refresh); host.post(refresh)
        }
    }
    private val actions = DanmakuActions(activity, repository, ::current, { generation }, ::choose, ::selected, ::runAction)

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

    fun menuOption(): Pair<Option, () -> Unit> = Option(activity.getString(R.string.settings_section_danmaku), status()) to {
        val entries = buildList<Pair<Option, () -> Unit>> {
            add(Option(activity.getString(R.string.danmaku_enabled), activity.getString(if (settings.enabled) R.string.on else R.string.off)) to {
                settings.enabled = !settings.enabled
            })
            if (settings.enabled) {
                add(Option(activity.getString(R.string.danmaku_search)) to { actions.search() })
                add(Option(activity.getString(R.string.danmaku_switch_source)) to { actions.sources() })
                add(Option(activity.getString(R.string.danmaku_reload)) to { reload(force = true) })
                add(Option(activity.getString(R.string.danmaku_restore_auto)) to ::restore)
            }
        }
        OptionsPanel(activity, status(), entries.map { it.first }) { entries[it].second() }.show()
    }

    private fun reload(force: Boolean = false) {
        generation++
        job?.cancel()
        actionJob?.cancel()
        loading = false
        result = null
        overlay.clear()
        val request = media ?: return
        if (!settings.enabled || testing || released) return
        val version = generation
        loading = true
        job = activity.lifecycleScope.launch {
            val loaded = repository.load(request, force)
            if (version != generation || released) return@launch
            loading = false
            result = loaded
            if (loaded is DanmakuLoad.Loaded) overlay.setComments(loaded.comments)
            repository.serverFailure?.let { DebugLog.i("LumyDanmaku", "server plugin failed: $it") }
        }
    }

    private fun current(): Pair<DanmakuMedia, DanmakuChoice>? {
        if (!settings.enabled || testing || released) return null
        val item = media ?: return null
        val source = when (val value = result) {
            is DanmakuLoad.Loaded -> value.source
            is DanmakuLoad.NeedsMatch -> value.source
            is DanmakuLoad.Empty -> value.source
            else -> store.choice(item.key) ?: when (sources.onlineSource) {
                DanmakuOnlineSource.OFFICIAL -> DanmakuChoice(DanmakuSourceKind.OFFICIAL, "official:dandanplay")
                DanmakuOnlineSource.CUSTOM -> runCatching { DanmakuChoice(DanmakuSourceKind.CUSTOM, services.custom().identity) }
                    .getOrElse { DanmakuChoice(DanmakuSourceKind.CUSTOM, "unconfigured") }
            }
        } ?: return null
        return item to source
    }

    private fun choose(choice: DanmakuChoice) {
        val request = media ?: return
        store.choose(request.key, choice)
        reload()
    }

    private fun selected(anime: DanmakuAnime, episode: DanmakuEpisode, choice: DanmakuChoice) {
        val request = media ?: return
        store.remember(request.key, DanmakuMatch(choice, anime, episode, request.year, request.season, request.number))
        reload()
    }

    private fun restore() { media?.let { store.restoreAuto(it.key) }; reload() }

    private fun runAction(block: suspend () -> Unit) {
        if (!settings.enabled || released || testing) return
        actionJob?.cancel()
        val token = generation
        actionJob = activity.lifecycleScope.launch {
            try { if (token == generation) block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: DanmakuException) { toast(danmakuErrorText(activity, error.reason)) }
            catch (_: Exception) { toast(activity.getString(R.string.danmaku_status_error)) }
        }
    }

    private fun status(): String = when {
        !settings.enabled -> activity.getString(R.string.off)
        loading -> activity.getString(R.string.danmaku_loading)
        else -> when (val value = result) {
            is DanmakuLoad.Loaded -> "${sourceLabel(value.source)} · ${activity.getString(R.string.danmaku_loaded, value.comments.size)}"
            is DanmakuLoad.NeedsMatch -> activity.getString(R.string.danmaku_needs_match)
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
        generation++; job?.cancel(); actionJob?.cancel(); result = null; media = null; loading = false
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

package com.hesier.lumy.ui.player

import android.view.KeyEvent
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.R
import com.hesier.lumy.core.HubStyle
import com.hesier.lumy.core.MediaDetail
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.databinding.ActivityPlayerBinding
import com.hesier.lumy.ui.ThemedActivity
import com.hesier.lumy.ui.widget.CardAdapter
import com.hesier.lumy.ui.widget.StartAlignedLayoutManager
import com.hesier.lumy.ui.widget.attempt
import com.hesier.lumy.util.DebugLog
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Owns row data and focus; playback changes stay with the Activity. */
internal class PlayerEpisodeController(
    private val activity: ThemedActivity,
    private val binding: ActivityPlayerBinding,
    private val positionMs: () -> Long,
    private val onSeek: (Long) -> Unit,
    private val onSwitch: (MediaItem, Long) -> Unit,
    private val onFocusMoved: (Int) -> Unit,
) {
    private lateinit var server: MediaServer
    private lateinit var detail: MediaDetail
    private val item get() = detail.item
    private var playlistId: String? = null
    private var seasonEpisodes: List<MediaItem> = emptyList()
    private var episodesAdapter: CardAdapter? = null
    private var chapterAdapter: ChapterAdapter? = null
    private var playlistJob: Deferred<List<MediaItem>?>? = null
    private var rowJob: Job? = null
    private var nextJob: Job? = null
    private var lastButton = 0
    private var generation = 0

    var nextEpisode: MediaItem? = null
        private set

    fun bind(server: MediaServer, detail: MediaDetail, playlistId: String?) {
        reset()
        this.server = server
        this.detail = detail
        this.playlistId = playlistId
        loadRowItems()
        val currentGeneration = generation
        nextJob = activity.lifecycleScope.launch {
            val playlist = playlistItems()
            val next = when {
                playlist != null -> playlist.getOrNull(playlist.indexOfFirst { it.id == item.id } + 1)
                item.type == MediaType.EPISODE -> attempt { server.nextEpisode(item) }.getOrNull()
                else -> null
            }
            if (generation == currentGeneration) nextEpisode = next
        }
    }

    fun reset() {
        generation++
        rowJob?.cancel()
        nextJob?.cancel()
        playlistJob?.cancel()
        playlistJob = null
        nextEpisode = null
        seasonEpisodes = emptyList()
        chapterAdapter = null
        // Reload playlist progress after switching back to a partially watched item.
        hideRow()
    }

    private suspend fun playlistItems(): List<MediaItem>? {
        val id = playlistId ?: return null
        val job = playlistJob ?: activity.lifecycleScope.async {
            attempt { server.containerItems(MediaItem(id, MediaType.PLAYLIST, "")) }.getOrNull()
                ?.filter { it.type == MediaType.MOVIE || it.type == MediaType.EPISODE }
        }.also { playlistJob = it }
        return job.await()
    }

    private fun loadRowItems() {
        val seasonId = item.parentId?.takeIf { item.type == MediaType.EPISODE }
        if (seasonId == null && playlistId == null) {
            bindChapters()
            updateRowHint()
            return
        }
        val currentGeneration = generation
        rowJob = activity.lifecycleScope.launch {
            val list = playlistItems() ?: run {
                if (seasonId == null) return@launch
                val result = attempt { server.children(seasonId) }
                DebugLog.i(TAG, "season $seasonId episodes=${result.getOrNull()?.size} error=${result.exceptionOrNull()}")
                result.getOrDefault(emptyList())
            }
            if (generation != currentGeneration) return@launch
            bindEpisodes(list)
            updateRowHint()
        }
    }

    private fun bindChapters() {
        if (detail.chapters.size <= 1) return
        chapterAdapter = ChapterAdapter(server, detail.chapters) {
            onSeek(it.startMs)
            chapterAdapter?.selected = detail.chapters.indexOf(it)
        }.also {
            binding.episodes.layoutManager = StartAlignedLayoutManager(activity, RecyclerView.HORIZONTAL)
            binding.episodes.adapter = it
            binding.episodes.preferredPosition = { currentChapter() }
        }
    }

    private fun bindEpisodes(list: List<MediaItem>) {
        seasonEpisodes = list
        val adapter = episodesAdapter ?: CardAdapter(
            server, HubStyle.LANDSCAPE,
            onFocused = {},
            onClick = { ep -> if (ep.id != item.id) onSwitch(ep, ep.viewOffsetMs?.takeIf { it > 30_000 } ?: 0) },
        ).also { episodesAdapter = it }
        if (binding.episodes.adapter !== adapter) {
            binding.episodes.layoutManager = StartAlignedLayoutManager(activity, RecyclerView.HORIZONTAL)
            binding.episodes.adapter = adapter
            binding.episodes.preferredPosition = { episodesAdapter?.indexOf(item.id) ?: -1 }
        }
        adapter.submit(list)
        adapter.selectedId = item.id
    }

    private fun updateRowHint() {
        val label = when {
            chapterAdapter != null -> R.string.player_chapters_hint
            seasonEpisodes.isEmpty() -> null
            playlistId != null -> R.string.player_playlist_hint
            else -> R.string.player_episodes_hint
        }
        binding.rowHint.visibility = if (label != null && binding.episodes.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        binding.rowHint.text = label?.let { activity.getString(it) }
    }

    fun currentChapter(): Int = detail.chapters.indexOfLast { positionMs() >= it.startMs }.coerceAtLeast(0)

    fun hideRow() {
        binding.episodes.visibility = View.GONE
        updateRowHint()
    }

    fun handleKey(event: KeyEvent): Boolean {
        val focused = activity.currentFocus ?: return false
        val inButtons = focused.parent === binding.buttons
        val inEpisodes = focused.parent === binding.episodes
        if (event.action == KeyEvent.ACTION_DOWN && (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
            DebugLog.i(TAG, "key ${event.keyCode} focus=${focused.javaClass.simpleName} inButtons=$inButtons inEpisodes=$inEpisodes episodes=${seasonEpisodes.size}")
        }
        return when {
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && inButtons -> {
                if (event.action == KeyEvent.ACTION_DOWN) showRow(focused)
                true
            }
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP && inEpisodes -> {
                if (event.action == KeyEvent.ACTION_DOWN) focusButtons()
                true
            }
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && inEpisodes -> true
            else -> false
        }
    }

    private fun showRow(focused: View) {
        val chapters = chapterAdapter
        if (chapters == null && seasonEpisodes.isEmpty()) return
        lastButton = binding.buttons.indexOfChild(focused)
        val index = if (chapters != null) {
            currentChapter().also { chapters.selected = it }
        } else {
            seasonEpisodes.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        }
        binding.episodes.visibility = View.VISIBLE
        updateRowHint()
        (binding.episodes.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(index, 0)
        val currentGeneration = generation
        binding.episodes.post {
            if (generation != currentGeneration || binding.episodes.visibility != View.VISIBLE) return@post
            if (binding.episodes.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() == true) {
                onFocusMoved(View.FOCUS_DOWN)
            }
        }
    }

    private fun focusButtons() {
        if (binding.buttons.getChildAt(lastButton.coerceIn(0, binding.buttons.childCount - 1))?.requestFocus() == true) {
            onFocusMoved(View.FOCUS_UP)
        }
        hideRow()
    }

    private companion object {
        const val TAG = "LumyPlayer"
    }
}

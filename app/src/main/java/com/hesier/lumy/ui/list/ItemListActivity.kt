package com.hesier.lumy.ui.list

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import com.hesier.lumy.ui.ThemedActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.core.HubStyle
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.databinding.ActivityItemListBinding
import com.hesier.lumy.ui.Navigator
import com.hesier.lumy.ui.player.PlayerActivity
import com.hesier.lumy.ui.widget.CardAdapter
import com.hesier.lumy.ui.widget.KeyRepeatThrottle
import com.hesier.lumy.ui.widget.ItemMenu
import com.hesier.lumy.ui.widget.addActionButton
import com.hesier.lumy.ui.widget.attempt
import com.hesier.lumy.ui.widget.hide
import com.hesier.lumy.ui.widget.readableMessage
import com.hesier.lumy.ui.widget.showLoading
import com.hesier.lumy.ui.widget.showMessage
import kotlinx.coroutines.launch

/** 通用的海报网格页：合集、播放列表、演员作品、媒体库的合集列表、全部播放列表 */
class ItemListActivity : ThemedActivity() {

    private enum class Kind { CONTAINER, PERSON, LIBRARY_COLLECTIONS, PLAYLISTS, PICK_SEASON }

    private lateinit var binding: ActivityItemListBinding
    private lateinit var server: MediaServer
    private lateinit var adapter: CardAdapter
    private var items: List<MediaItem> = emptyList()

    private val kind get() = Kind.valueOf(intent.getStringExtra(EXTRA_KIND)!!)
    private val itemId get() = intent.getStringExtra(EXTRA_ID).orEmpty()
    private val itemType get() = intent.getStringExtra(EXTRA_TYPE)?.let { MediaType.valueOf(it) }
    private val libraryId get() = intent.getStringExtra(EXTRA_LIBRARY).orEmpty()

    private val keyThrottle = KeyRepeatThrottle()

    /** 长按方向键时放慢移动速度 */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        keyThrottle.shouldDrop(event) || super.dispatchKeyEvent(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        server = LumyApp.instance.currentServer() ?: return finish()
        binding = ActivityItemListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.title.text = intent.getStringExtra(EXTRA_TITLE)
        adapter = CardAdapter(server, HubStyle.POSTER, onFocused = {}, onClick = ::onItemClick, onLongClick = ::showMenu)
        binding.grid.layoutManager = GridLayoutManager(this, spanCount())
        binding.grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            private val gap = resources.getDimensionPixelSize(R.dimen.card_gap) + 6
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.bottom = gap
            }
        })
        binding.grid.adapter = adapter
        load()
    }

    private fun spanCount(): Int {
        val res = resources
        val width = res.displayMetrics.widthPixels - ((64 + 48) * res.displayMetrics.density).toInt()
        val cell = res.getDimensionPixelSize(R.dimen.poster_width) + res.getDimensionPixelSize(R.dimen.card_gap)
        return (width / cell).coerceAtLeast(3)
    }

    private fun load() {
        binding.state.showLoading()
        lifecycleScope.launch {
            val result = attempt {
                when (kind) {
                    Kind.CONTAINER -> server.containerItems(MediaItem(itemId, itemType ?: MediaType.COLLECTION, ""))
                    Kind.PERSON -> server.personItems(libraryId, itemId)
                    Kind.LIBRARY_COLLECTIONS -> server.collections(libraryId)
                    Kind.PLAYLISTS -> server.playlists()
                    Kind.PICK_SEASON -> server.children(itemId)
                }
            }
            items = result.getOrElse {
                binding.state.showMessage(getString(R.string.load_failed, it.readableMessage()), getString(R.string.retry)) { load() }
                return@launch
            }
            binding.count.text = resources.getQuantityString(R.plurals.library_count, items.size, items.size)
            adapter.submit(items)
            // 选季时焦点放在当前季上
            val current = intent.getStringExtra(EXTRA_SELECTED)?.let { id -> items.indexOfFirst { it.id == id } } ?: -1
            adapter.selectedId = intent.getStringExtra(EXTRA_SELECTED)
            renderActions()
            if (items.isEmpty()) {
                binding.state.showMessage(getString(R.string.home_empty))
            } else {
                binding.state.hide()
                binding.grid.scrollToPosition(current.coerceAtLeast(0))
                binding.grid.post { binding.grid.findViewHolderForAdapterPosition(current.coerceAtLeast(0))?.itemView?.requestFocus() }
            }
        }
    }

    private val isPlaylist get() = kind == Kind.CONTAINER && itemType == MediaType.PLAYLIST

    private fun onItemClick(item: MediaItem) {
        if (kind == Kind.PICK_SEASON) {
            setResult(RESULT_OK, Intent().putExtra(RESULT_SEASON_ID, item.id))
            finish()
        } else if (isPlaylist && item.playable) {
            // 播放列表里点一项就从它开始在列表里播放（接着上次的进度）；菜单键 / 长按里可以进详情
            startActivity(PlayerActivity.intent(this, item.id, 0, item.viewOffsetMs ?: 0, playlistId = itemId))
        } else {
            Navigator.open(this, item)
        }
    }

    /** 播放列表可以从头连续播放 */
    private fun renderActions() {
        binding.actions.removeAllViews()
        if (isPlaylist && items.isNotEmpty()) {
            binding.actions.addActionButton(R.drawable.ic_play, getString(R.string.play_all)) {
                val first = items.firstOrNull { it.playable } ?: return@addActionButton
                startActivity(PlayerActivity.intent(this, first.id, 0, 0, playlistId = itemId))
            }
        }
    }

    private fun showMenu(item: MediaItem) {
        if (item.type == MediaType.COLLECTION || item.type == MediaType.PLAYLIST) return
        ItemMenu.show(this, server, item) { changed -> adapter.update(changed) }
    }

    companion object {
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_ID = "id"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_LIBRARY = "library"
        private const val EXTRA_SELECTED = "selected"
        const val RESULT_SEASON_ID = "season_id"

        /** 大卡片显示剧的所有季，选中后把季 id 返回给剧页面 */
        fun pickSeason(context: Context, showId: String, showTitle: String, currentSeasonId: String?): Intent =
            Intent(context, ItemListActivity::class.java)
                .putExtra(EXTRA_KIND, Kind.PICK_SEASON.name)
                .putExtra(EXTRA_ID, showId)
                .putExtra(EXTRA_SELECTED, currentSeasonId)
                .putExtra(EXTRA_TITLE, context.getString(R.string.seasons_all_title, showTitle))

        fun container(context: Context, item: MediaItem): Intent = Intent(context, ItemListActivity::class.java)
            .putExtra(EXTRA_KIND, Kind.CONTAINER.name)
            .putExtra(EXTRA_ID, item.id)
            .putExtra(EXTRA_TYPE, item.type.name)
            .putExtra(EXTRA_TITLE, item.title)

        fun person(context: Context, person: MediaItem, libraryId: String): Intent = Intent(context, ItemListActivity::class.java)
            .putExtra(EXTRA_KIND, Kind.PERSON.name)
            .putExtra(EXTRA_ID, person.id)
            .putExtra(EXTRA_LIBRARY, libraryId)
            .putExtra(EXTRA_TITLE, context.getString(R.string.person_works, person.title))

        fun libraryCollections(context: Context, libraryId: String, libraryTitle: String): Intent =
            Intent(context, ItemListActivity::class.java)
                .putExtra(EXTRA_KIND, Kind.LIBRARY_COLLECTIONS.name)
                .putExtra(EXTRA_LIBRARY, libraryId)
                .putExtra(EXTRA_TITLE, "$libraryTitle · ${context.getString(R.string.collections)}")

        fun playlists(context: Context): Intent = Intent(context, ItemListActivity::class.java)
            .putExtra(EXTRA_KIND, Kind.PLAYLISTS.name)
            .putExtra(EXTRA_TITLE, context.getString(R.string.nav_playlists))
    }
}

private val MediaItem.playable get() = type == MediaType.MOVIE || type == MediaType.EPISODE

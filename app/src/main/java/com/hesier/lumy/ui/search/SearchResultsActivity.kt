package com.hesier.lumy.ui.search

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.core.HubStyle
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.SearchScope
import com.hesier.lumy.databinding.ActivitySearchResultsBinding
import com.hesier.lumy.ui.Navigator
import com.hesier.lumy.ui.ThemedActivity
import com.hesier.lumy.ui.widget.CardAdapter
import com.hesier.lumy.ui.widget.ItemMenu
import com.hesier.lumy.ui.widget.KeyRepeatThrottle
import com.hesier.lumy.ui.widget.attempt
import com.hesier.lumy.ui.widget.readableMessage
import kotlinx.coroutines.launch

/** Full search pages always come from searchItems, never from the preview hub. */
class SearchResultsActivity : ThemedActivity() {
    private lateinit var binding: ActivitySearchResultsBinding
    private lateinit var server: MediaServer
    private lateinit var cards: CardAdapter
    private lateinit var layout: GridLayoutManager
    private val items = ArrayList<MediaItem>()
    private val keyThrottle = KeyRepeatThrottle()
    private var offset = 0
    private var hasMore = true
    private var loading = false
    private var focusedPosition = 0
    private val query get() = intent.getStringExtra(EXTRA_QUERY).orEmpty()
    private val scope get() = SearchScope(
        MediaType.valueOf(requireNotNull(intent.getStringExtra(EXTRA_TYPE))),
        intent.getStringExtra(EXTRA_LIBRARY),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (com.hesier.lumy.ui.login.PlexHomeNavigation.gate(this)) return
        server = LumyApp.instance.currentServer() ?: return finish()
        binding = ActivitySearchResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.title.text = getString(R.string.search_results_title, intent.getStringExtra(EXTRA_TITLE), query)
        focusedPosition = savedInstanceState?.getInt(STATE_POSITION) ?: 0
        configureGrid()
        binding.more.setOnClickListener { loadPage() }
        binding.more.setOnKeyListener { _, key, event ->
            if (key != KeyEvent.KEYCODE_DPAD_UP || items.isEmpty()) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN) focusCard(focusedPosition)
            true
        }
        loadPage()
    }

    private fun configureGrid() {
        val width = resources.displayMetrics.widthPixels - (112 * resources.displayMetrics.density).toInt()
        val cell = resources.getDimensionPixelSize(R.dimen.poster_width) + resources.getDimensionPixelSize(R.dimen.card_gap)
        layout = GridLayoutManager(this, (width / cell).coerceAtLeast(3))
        cards = CardAdapter(server, HubStyle.POSTER,
            onFocused = { focusedPosition = cards.indexOf(it.id) },
            onClick = { Navigator.open(this, it) },
            onLongClick = { ItemMenu.show(this, server, it) { changed ->
                val index = items.indexOfFirst { item -> item.id == changed.id }
                if (index >= 0) items[index] = changed
                cards.update(changed)
            } })
        binding.grid.layoutManager = layout
        binding.grid.adapter = cards
        binding.grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.bottom = resources.getDimensionPixelSize(R.dimen.card_gap)
            }
        })
        binding.grid.preferredPosition = { focusedPosition.coerceIn(0, (items.size - 1).coerceAtLeast(0)) }
    }

    private fun loadPage() {
        if (loading || !hasMore) return
        loading = true
        binding.status.visibility = View.GONE
        binding.more.visibility = View.VISIBLE
        binding.more.text = getString(R.string.search_loading_more)
        val requestedOffset = offset
        lifecycleScope.launch {
            val result = attempt { server.searchItems(query, scope, requestedOffset, PAGE_SIZE) }
            loading = false
            val page = result.getOrElse {
                binding.status.text = getString(R.string.load_failed, it.readableMessage())
                binding.status.visibility = View.VISIBLE
                binding.more.text = getString(R.string.retry)
                if (items.isEmpty()) binding.more.requestFocus()
                return@launch
            }
            // Advance by the server page size even when duplicate IDs are omitted from the grid.
            offset = requestedOffset + page.items.size
            val knownIds = items.mapTo(HashSet()) { it.id }
            val firstNewPosition = items.size
            items.addAll(page.items.filter { knownIds.add(it.id) })
            hasMore = page.items.isNotEmpty() && page.hasMore
            cards.submit(items)
            renderPage(firstNewPosition)
        }
    }

    private fun renderPage(firstNewPosition: Int) {
        binding.count.text = getString(R.string.search_loaded_count, items.size)
        val focusNewPage = firstNewPosition == 0 || binding.more.hasFocus()
        binding.more.text = getString(R.string.search_load_more)
        binding.more.visibility = if (hasMore) View.VISIBLE else View.GONE
        if (items.isEmpty()) {
            binding.status.text = getString(R.string.search_empty, query)
            binding.status.visibility = View.VISIBLE
        } else if (focusNewPage) {
            focusCard(if (firstNewPosition == 0) focusedPosition else firstNewPosition)
        }
    }

    private fun focusCard(position: Int) {
        val target = position.coerceIn(0, items.lastIndex)
        binding.grid.doOnNextLayout {
            binding.grid.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
        }
        binding.grid.scrollToPosition(target)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyThrottle.shouldDrop(event)) return true
        if (::binding.isInitialized && event.action == KeyEvent.ACTION_DOWN && binding.grid.hasFocus()) {
            val position = binding.grid.findContainingViewHolder(currentFocus ?: binding.grid)?.bindingAdapterPosition ?: -1
            if (position >= 0 && atBoundary(position, event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun atBoundary(position: Int, key: Int): Boolean = when (key) {
        KeyEvent.KEYCODE_DPAD_LEFT -> position % layout.spanCount == 0
        KeyEvent.KEYCODE_DPAD_RIGHT -> position % layout.spanCount == layout.spanCount - 1 || position == items.lastIndex
        KeyEvent.KEYCODE_DPAD_UP -> position < layout.spanCount
        KeyEvent.KEYCODE_DPAD_DOWN -> position + layout.spanCount >= items.size && !hasMore
        else -> false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_POSITION, focusedPosition)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val PAGE_SIZE = 60
        private const val EXTRA_QUERY = "query"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_LIBRARY = "library"
        private const val EXTRA_TITLE = "title"
        private const val STATE_POSITION = "focused_position"

        fun intent(context: Context, query: String, scope: SearchScope, title: String): Intent =
            Intent(context, SearchResultsActivity::class.java)
                .putExtra(EXTRA_QUERY, query)
                .putExtra(EXTRA_TYPE, scope.type.name)
                .putExtra(EXTRA_LIBRARY, scope.libraryId)
                .putExtra(EXTRA_TITLE, title)
    }
}

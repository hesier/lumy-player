package com.hesier.lumy.ui.favorites

import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.R
import com.hesier.lumy.core.HubStyle
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.databinding.FragmentFavoritesBinding
import com.hesier.lumy.ui.home.HomeHost
import com.hesier.lumy.ui.home.ItemChangeListener
import com.hesier.lumy.ui.widget.CardAdapter
import com.hesier.lumy.ui.widget.addActionButton
import com.hesier.lumy.ui.widget.attempt
import com.hesier.lumy.ui.widget.hide
import com.hesier.lumy.ui.widget.readableMessage
import com.hesier.lumy.ui.widget.showLoading
import com.hesier.lumy.ui.widget.showMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class FavoritesFragment : Fragment(), ItemChangeListener {
    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!
    private val host get() = requireActivity() as HomeHost
    private lateinit var adapter: CardAdapter
    private val items = mutableListOf<MediaItem>()
    private var category = 0
    private var focusedId: String? = null
    private var focusedPosition = 0
    private var total = 0
    private var nextOffset = 0
    private var loadJob: Job? = null
    private var generation = 0
    private var returningFromDetail = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        category = savedInstanceState?.getInt(KEY_CATEGORY) ?: 0
        focusedId = savedInstanceState?.getString(KEY_FOCUS)
        focusedPosition = savedInstanceState?.getInt(KEY_POSITION) ?: 0
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = CardAdapter(host.server, HubStyle.POSTER, { item ->
            focusedId = item.id
            focusedPosition = adapter.indexOf(item.id).coerceAtLeast(0)
            host.showBackdrop(item)
        }, { item ->
            returningFromDetail = true
            host.openItem(item)
        }, host::showItemMenu)
        setupGrid()
        TYPES.forEachIndexed { index, _ ->
            binding.categories.addActionButton(R.drawable.ic_favorite, getString(LABELS[index])) {
                selectCategory(index)
            }.apply {
                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) selectCategory(index)
                }
                if (index == TYPES.lastIndex) {
                    id = View.generateViewId()
                    nextFocusRightId = id
                }
            }
        }
        updateCategories()
    }

    override fun onResume() {
        super.onResume()
        val restoreFocus = returningFromDetail
        returningFromDetail = false
        reload(restoreFocus)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CATEGORY, category)
        outState.putString(KEY_FOCUS, focusedId)
        outState.putInt(KEY_POSITION, focusedPosition)
    }

    override fun onDestroyView() {
        generation++
        loadJob?.cancel()
        _binding = null
        super.onDestroyView()
    }

    private fun setupGrid() {
        val columns = spanCount()
        binding.grid.layoutManager = object : GridLayoutManager(requireContext(), columns) {
            override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
                val position = binding.grid.findContainingViewHolder(focused)?.bindingAdapterPosition ?: -1
                if (direction == View.FOCUS_UP && position in 0 until spanCount) {
                    return binding.categories.getChildAt(category)
                }
                return super.onInterceptFocusSearch(focused, direction)
            }
        }
        binding.grid.adapter = adapter
        binding.grid.setHasFixedSize(true)
        binding.grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.bottom = resources.getDimensionPixelSize(R.dimen.card_gap) + 6
            }
        })
        binding.grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val last = (recyclerView.layoutManager as GridLayoutManager).findLastVisibleItemPosition()
                if (last >= items.size - columns * 2) loadMore()
            }
        })
    }

    private fun spanCount(): Int {
        val width = resources.displayMetrics.widthPixels - resources.getDimensionPixelSize(R.dimen.sidebar_collapsed) -
            (48 * resources.displayMetrics.density).toInt()
        val cell = resources.getDimensionPixelSize(R.dimen.poster_width) + resources.getDimensionPixelSize(R.dimen.card_gap)
        return (width / cell).coerceAtLeast(3)
    }

    fun focusCategoryOnBack(): Boolean {
        val currentBinding = _binding ?: return false
        if (currentBinding.categories.hasFocus()) return false
        return currentBinding.categories.getChildAt(category).requestFocus()
    }

    private fun selectCategory(index: Int) {
        if (category == index) return
        category = index
        focusedId = null
        focusedPosition = 0
        items.clear()
        total = 0
        nextOffset = 0
        adapter.submit(emptyList())
        binding.count.text = null
        host.showBackdrop(null)
        updateCategories()
        reload(false)
    }

    private fun updateCategories() {
        TYPES.indices.forEach { index ->
            val tab = binding.categories.getChildAt(index)
            tab.isSelected = index == category
            tab.findViewById<ImageView>(R.id.icon).setImageResource(
                if (index == category) R.drawable.ic_favorite_filled else R.drawable.ic_favorite,
            )
        }
    }

    private fun reload(restoreFocus: Boolean) {
        loadJob?.cancel()
        val gen = ++generation
        if (items.isEmpty()) binding.state.showLoading()
        // Reload the loaded window so detail changes preserve both category and position.
        val limit = maxOf(PAGE_SIZE, items.size, focusedPosition + 1)
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = attempt { host.server.favorites(TYPES[category], 0, limit) }
            if (gen != generation || _binding == null) return@launch
            result.onSuccess { page ->
                val hadGridFocus = binding.grid.hasFocus()
                nextOffset = page.offset + page.items.size
                total = if (page.items.isEmpty()) 0 else page.total
                items.clear()
                items.addAll(page.items.distinctBy { it.id })
                adapter.submit(items.toList())
                updateState(hadGridFocus || restoreFocus)
                if (restoreFocus) {
                    if (items.isEmpty()) binding.categories.getChildAt(category).requestFocus()
                    else restoreCardFocus()
                }
            }.onFailure { showError(it) }
            loadJob = null
            if (result.isSuccess) scheduleNextPage()
        }
    }

    private fun loadMore() {
        if (loadJob?.isActive == true || nextOffset >= total) return
        val gen = generation
        val offset = nextOffset
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = attempt { host.server.favorites(TYPES[category], offset, PAGE_SIZE) }
            if (gen != generation || _binding == null) return@launch
            result.onSuccess { page ->
                nextOffset = page.offset + page.items.size
                total = if (page.items.isEmpty()) nextOffset else page.total
                val knownIds = items.mapTo(HashSet()) { it.id }
                items.addAll(page.items.filter { knownIds.add(it.id) })
                adapter.submit(items.toList())
                updateState()
            }.onFailure { showError(it) }
            loadJob = null
            if (result.isSuccess) scheduleNextPage()
        }
    }

    private fun scheduleNextPage() {
        val grid = binding.grid
        grid.post {
            if (_binding?.grid !== grid) return@post
            val layout = grid.layoutManager as GridLayoutManager
            if (layout.findLastVisibleItemPosition() >= items.size - spanCount() * 2) loadMore()
        }
    }

    private fun updateState(restoreEmptyFocus: Boolean = false) {
        binding.count.text = resources.getQuantityString(R.plurals.library_count, total, total)
        if (items.isEmpty()) {
            binding.state.showMessage(getString(R.string.favorites_empty))
            host.showBackdrop(null)
            if (restoreEmptyFocus) binding.categories.getChildAt(category).requestFocus()
        } else {
            binding.state.hide()
        }
    }

    private fun showError(error: Throwable) {
        binding.state.showMessage(getString(R.string.load_failed, error.readableMessage()), getString(R.string.retry)) {
            reload(true)
        }
    }

    private fun restoreCardFocus() {
        val index = items.indexOfFirst { it.id == focusedId }.takeIf { it >= 0 }
            ?: focusedPosition.coerceIn(items.indices)
        val grid = binding.grid
        grid.scrollToPosition(index)
        grid.post { grid.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
    }

    override fun onItemChanged(item: MediaItem) {
        if (_binding == null) return
        val position = items.indexOfFirst { it.id == item.id }
        if (position < 0) return
        if (item.favorite) {
            items[position] = item
            adapter.update(item)
            return
        }
        items.removeAt(position)
        total = (total - 1).coerceAtLeast(0)
        adapter.submit(items.toList())
        updateState(true)
        if (items.isEmpty()) binding.categories.getChildAt(category).requestFocus()
        else restoreCardFocus()
        // The server offsets shifted; refill the loaded window before appending another page.
        reload(false)
    }

    companion object {
        private const val PAGE_SIZE = 60
        private const val KEY_CATEGORY = "category"
        private const val KEY_FOCUS = "focusedId"
        private const val KEY_POSITION = "focusedPosition"
        private val TYPES = listOf(MediaType.MOVIE, MediaType.SHOW, MediaType.EPISODE)
        private val LABELS = listOf(R.string.favorites_movies, R.string.favorites_shows, R.string.favorites_episodes)
    }
}

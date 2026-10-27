package com.hesier.lumy.ui.library

import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.core.FilterOption
import com.hesier.lumy.core.HubStyle
import com.hesier.lumy.core.Library
import com.hesier.lumy.core.LibraryQuery
import com.hesier.lumy.core.LetterBucket
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.ResolutionFilter
import com.hesier.lumy.core.SortOrder
import com.hesier.lumy.core.WatchFilter
import com.hesier.lumy.R
import com.hesier.lumy.databinding.FragmentLibraryBinding
import com.hesier.lumy.ui.home.HomeHost
import com.hesier.lumy.ui.home.ItemChangeListener
import com.hesier.lumy.ui.list.ItemListActivity
import com.hesier.lumy.ui.widget.CardAdapter
import com.hesier.lumy.ui.widget.Option
import com.hesier.lumy.ui.widget.OptionsPanel
import com.hesier.lumy.ui.widget.addActionButton
import com.hesier.lumy.ui.widget.attempt
import com.hesier.lumy.ui.widget.hide
import com.hesier.lumy.ui.widget.readableMessage
import com.hesier.lumy.ui.widget.showLoading
import com.hesier.lumy.ui.widget.showMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 媒体库海报网格。数据稀疏加载：先拿到总数占好位置，滚动或跳转到哪页就加载哪页，
 * 所以按首字母跳到几百项之后也不用把前面的都加载一遍。
 */
class LibraryFragment : Fragment(), ItemChangeListener {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val host get() = requireActivity() as HomeHost

    /** 侧边栏据此判断当前是哪个媒体库 */
    val libraryId get() = requireArguments().getString(ARG_ID)!!
    private val isMovieLibrary get() = requireArguments().getString(ARG_TYPE) == MediaType.MOVIE.name
    private lateinit var adapter: CardAdapter
    private var query = LibraryQuery()
    private var genres: List<FilterOption>? = null
    private val loadingPages = HashSet<Int>()
    /** 每次换筛选条件都递增，丢弃旧条件下还没返回的请求 */
    private var generation = 0
    private var loadJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.title.text = requireArguments().getString(ARG_TITLE)
        adapter = CardAdapter(
            host.server, HubStyle.POSTER, host::showBackdrop, host::openItem, host::showItemMenu,
            onMissing = { loadPage(it / PAGE_SIZE) },
        )
        binding.grid.layoutManager = GridLayoutManager(requireContext(), spanCount())
        binding.grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            private val gap = resources.getDimensionPixelSize(R.dimen.card_gap) + 6
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.bottom = gap
            }
        })
        binding.grid.adapter = adapter
        binding.grid.setItemViewCacheSize(12)
        renderFilters()
        reload()
        checkCollections()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onItemChanged(item: MediaItem) = adapter.update(item)

    /** 列数按可用宽度算，不同分辨率的电视都能排满 */
    private fun spanCount(): Int {
        val res = resources
        val density = res.displayMetrics.density
        val width = res.displayMetrics.widthPixels -
            res.getDimensionPixelSize(R.dimen.sidebar_collapsed) - ((24 + 8 + 56) * density).toInt()
        val cell = res.getDimensionPixelSize(R.dimen.poster_width) + res.getDimensionPixelSize(R.dimen.card_gap)
        return (width / cell).coerceAtLeast(3)
    }

    // ---- 加载 ----

    private fun reload() {
        generation++
        loadingPages.clear()
        loadJob?.cancel()
        binding.state.showLoading()
        val gen = generation
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val first = attempt { host.server.libraryItems(libraryId, 0, PAGE_SIZE, query) }.getOrElse {
                binding.state.showMessage(getString(R.string.load_failed, it.readableMessage()), getString(R.string.reconnect)) {
                    host.reconnect()
                }
                return@launch
            }
            if (gen != generation) return@launch
            binding.count.text = resources.getQuantityString(R.plurals.library_count, first.total, first.total)
            adapter.resize(first.total)
            adapter.fill(0, first.items)
            binding.grid.scrollToPosition(0)
            if (first.total == 0) {
                binding.state.showMessage(getString(R.string.home_empty))
                binding.filters.getChildAt(0)?.requestFocus()
            } else {
                binding.state.hide()
                // 回调里不能再取 binding：页面重建或切走时 View 可能已经销毁
                val grid = binding.grid
                grid.post { grid.getChildAt(0)?.requestFocus() }
            }
            loadLetters(gen)
        }
    }

    private fun loadPage(page: Int) {
        if (!loadingPages.add(page)) return
        val gen = generation
        viewLifecycleOwner.lifecycleScope.launch {
            val result = attempt { host.server.libraryItems(libraryId, page * PAGE_SIZE, PAGE_SIZE, query) }
            if (gen != generation || _binding == null) return@launch
            result.onSuccess { adapter.fill(it.offset, it.items) }
                // 失败时允许下次绑定再试
                .onFailure { loadingPages.remove(page) }
        }
    }

    // ---- 快速跳转 ----

    /** 只在按标题排序时显示；中日韩标题统一归到“中”，否则会有上百个首字 */
    private fun loadLetters(gen: Int) {
        binding.letters.removeAllViews()
        binding.lettersScroll.visibility = View.GONE
        if (query.sort != SortOrder.TITLE) return
        viewLifecycleOwner.lifecycleScope.launch {
            val buckets = attempt { host.server.letterIndex(libraryId, query) }.getOrNull() ?: return@launch
            if (gen != generation || _binding == null) return@launch
            val groups = groupLetters(buckets)
            if (groups.size < 2) return@launch
            groups.forEach { (label, position) -> addLetter(label, position) }
            binding.lettersScroll.visibility = View.VISIBLE
        }
    }

    private fun addLetter(label: String, position: Int) {
        val ctx = requireContext()
        val size = (28 * resources.displayMetrics.density).toInt()
        val tv = TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(ContextCompat.getColorStateList(ctx, R.color.letter_text))
            background = ContextCompat.getDrawable(ctx, R.drawable.letter_bg)
            isFocusable = true
            setOnClickListener { jumpTo(position) }
        }
        binding.letters.addView(tv, LinearLayout.LayoutParams(size, size).apply { bottomMargin = 2 })
    }

    private fun jumpTo(position: Int) {
        (binding.grid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(position, 0)
        val grid = binding.grid
        grid.post { grid.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
    }

    // ---- 筛选 ----

    private fun renderFilters() {
        val row = binding.filters
        row.removeAllViews()
        row.addActionButton(R.drawable.ic_sort, "${getString(R.string.sort)}：${sortLabel(query.sort)}") { pickSort() }
        row.addActionButton(R.drawable.ic_filter, watchLabel(query.watch)) { pickWatch() }
        row.addActionButton(R.drawable.ic_filter, genres?.firstOrNull { it.id == query.genreId }?.title ?: getString(R.string.filter_any_genre)) { pickGenre() }
        if (isMovieLibrary) {
            row.addActionButton(R.drawable.ic_hd, resolutionLabel(query.resolution)) { pickResolution() }
        }
        if (hasCollections) {
            row.addActionButton(R.drawable.ic_layers, getString(R.string.collections)) {
                startActivity(ItemListActivity.libraryCollections(requireContext(), libraryId, requireArguments().getString(ARG_TITLE).orEmpty()))
            }
        }
    }

    private var hasCollections = false

    /** 媒体库里有合集才显示“合集”按钮 */
    private fun checkCollections() {
        viewLifecycleOwner.lifecycleScope.launch {
            hasCollections = attempt { host.server.collections(libraryId) }.getOrDefault(emptyList()).isNotEmpty()
            if (hasCollections && _binding != null) renderFilters()
        }
    }

    private fun applyQuery(q: LibraryQuery) {
        if (q == query) return
        query = q
        renderFilters()
        reload()
        checkCollections()
    }

    private fun pickSort() {
        val orders = SortOrder.entries
        OptionsPanel(requireContext(), getString(R.string.sort), orders.map { Option(sortLabel(it), selected = it == query.sort) }) {
            applyQuery(query.copy(sort = orders[it]))
        }.show()
    }

    private fun pickWatch() {
        val values = WatchFilter.entries
        OptionsPanel(requireContext(), getString(R.string.filter_watch), values.map { Option(watchLabel(it), selected = it == query.watch) }) {
            applyQuery(query.copy(watch = values[it]))
        }.show()
    }

    private fun pickGenre() {
        viewLifecycleOwner.lifecycleScope.launch {
            val list = genres ?: attempt { host.server.genres(libraryId) }.getOrDefault(emptyList()).also { genres = it }
            val options = listOf(Option(getString(R.string.filter_any_genre), selected = query.genreId == null)) +
                list.map { Option(it.title, selected = it.id == query.genreId) }
            OptionsPanel(requireContext(), getString(R.string.filter_genre), options) {
                applyQuery(query.copy(genreId = if (it == 0) null else list[it - 1].id))
            }.show()
        }
    }

    private fun pickResolution() {
        val values = ResolutionFilter.entries
        OptionsPanel(requireContext(), getString(R.string.filter_resolution), values.map { Option(resolutionLabel(it), selected = it == query.resolution) }) {
            applyQuery(query.copy(resolution = values[it]))
        }.show()
    }

    private fun sortLabel(s: SortOrder) = getString(
        when (s) {
            SortOrder.TITLE -> R.string.sort_title
            SortOrder.ADDED -> R.string.sort_added
            SortOrder.RELEASED -> R.string.sort_released
            SortOrder.RATING -> R.string.sort_rating
            SortOrder.LAST_VIEWED -> R.string.sort_last_viewed
        },
    )

    private fun watchLabel(w: WatchFilter) = getString(
        when (w) {
            WatchFilter.ALL -> R.string.filter_all
            WatchFilter.UNWATCHED -> R.string.filter_unwatched
            WatchFilter.WATCHED -> R.string.filter_watched
        },
    )

    private fun resolutionLabel(r: ResolutionFilter) = when (r) {
        ResolutionFilter.ALL -> getString(R.string.filter_any_resolution)
        ResolutionFilter.UHD_4K -> "4K"
        ResolutionFilter.FHD_1080 -> "1080p"
        ResolutionFilter.HD_720 -> "720p"
        ResolutionFilter.SD -> "SD"
    }

    companion object {
        private const val ARG_ID = "id"
        private const val ARG_TITLE = "title"
        private const val ARG_TYPE = "type"
        private const val PAGE_SIZE = 60

        fun newInstance(library: Library) = LibraryFragment().apply {
            arguments = bundleOf(ARG_ID to library.id, ARG_TITLE to library.title, ARG_TYPE to library.type.name)
        }
    }
}

/**
 * 把服务端的首字符分组合并成跳转标签：符号和数字归到 #，拉丁字母各自一组，其余（中日韩等）归到“中”。
 * 返回 标签 → 该组第一项的位置。
 */
internal fun groupLetters(buckets: List<LetterBucket>): List<Pair<String, Int>> {
    val result = ArrayList<Pair<String, Int>>()
    var position = 0
    for (b in buckets) {
        val c = b.label.firstOrNull()
        val label = when {
            c == null -> "#"
            c.uppercaseChar() in 'A'..'Z' -> c.uppercaseChar().toString()
            c.code < 0x2E80 -> "#"
            else -> "中"
        }
        if (result.none { it.first == label }) result += label to position
        position += b.count
    }
    return result
}

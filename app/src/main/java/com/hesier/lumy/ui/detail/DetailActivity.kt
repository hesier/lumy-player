package com.hesier.lumy.ui.detail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.hesier.lumy.ui.ThemedActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.core.HubStyle
import com.hesier.lumy.core.MediaDetail
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.databinding.ActivityDetailBinding
import com.hesier.lumy.ui.Navigator
import com.hesier.lumy.ui.formatClock
import com.hesier.lumy.ui.formatDuration
import com.hesier.lumy.ui.list.ItemListActivity
import com.hesier.lumy.ui.player.PlayerActivity
import com.hesier.lumy.ui.widget.CardAdapter
import com.hesier.lumy.ui.widget.ItemMenu
import com.hesier.lumy.ui.widget.Option
import com.hesier.lumy.ui.widget.OptionsPanel
import com.hesier.lumy.ui.widget.StartAlignedLayoutManager
import com.hesier.lumy.ui.widget.KeyRepeatThrottle
import com.hesier.lumy.ui.widget.TextPanel
import com.hesier.lumy.ui.widget.Backdrop
import com.hesier.lumy.ui.widget.addActionButton
import com.hesier.lumy.ui.widget.addBadge
import com.hesier.lumy.ui.widget.attempt
import com.hesier.lumy.ui.widget.clipToRoundedBackground
import com.hesier.lumy.ui.widget.hide
import com.hesier.lumy.ui.widget.readableMessage
import com.hesier.lumy.ui.widget.showLoading
import com.hesier.lumy.ui.widget.showMessage
import coil.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 详情页。电影一页一部；剧集类（剧 / 季 / 单集）合并成同一个“剧页面”：
 * 上方显示当前选中的那一集（没选时显示剧本身），下方是分季标签和剧集列表。
 * 遥控器在剧集间左右移动时上方实时切换，不打开新页面，返回键直接离开这部剧。
 */
class DetailActivity : ThemedActivity() {

    private lateinit var binding: ActivityDetailBinding
    private lateinit var server: MediaServer

    /** 电影，或剧本身（剧页面） */
    private var main: MediaDetail? = null
    private val isSeries get() = main?.item?.type == MediaType.SHOW

    /** 剧页面里当前选中的剧集，null 表示上方显示剧本身 */
    private var selectedEpisode: MediaItem? = null
    private val episodeDetails = HashMap<String, MediaDetail>()
    private var episodeDetailJob: Job? = null
    private var headerJob: Job? = null

    private var seasons: List<MediaItem> = emptyList()
    private var currentSeasonId: String? = null
    private var seasonJob: Job? = null
    private var episodesAdapter: CardAdapter? = null

    private var versionIndex = 0
    private lateinit var fileInfo: FileInfoSection
    private var firstLoad = true

    /** “查看全部”选完季回来：切换到该季，焦点放在它的标签上 */
    private val seasonPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = result.data?.getStringExtra(ItemListActivity.RESULT_SEASON_ID) ?: return@registerForActivityResult
        val index = seasons.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return@registerForActivityResult
        switchSeason(seasons[index])
        binding.seasons.scrollToPosition(index)
        binding.seasons.post { binding.seasons.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
    }

    private val keyThrottle = KeyRepeatThrottle()

    /** 长按方向键时放慢移动速度 */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        keyThrottle.shouldDrop(event) || super.dispatchKeyEvent(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        server = LumyApp.instance.currentServer() ?: return finish()
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.posterFrame.clipToRoundedBackground()
        fileInfo = FileInfoSection(binding.fileInfoTitle, binding.fileInfo, LumyApp.instance.settings.detailShowFileInfo)
        // 标签很短，用普通布局：焦点移动时整行不滚动，不会左右闪动
        binding.seasons.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.summary.setOnClickListener {
            val text = binding.summary.text?.toString().orEmpty()
            if (text.isNotBlank()) TextPanel(this, binding.title.text.toString(), text).show()
        }
        binding.episodes.layoutManager = StartAlignedLayoutManager(this, RecyclerView.HORIZONTAL)
        binding.cast.layoutManager = StartAlignedLayoutManager(this, RecyclerView.HORIZONTAL)
        binding.similar.layoutManager = StartAlignedLayoutManager(this, RecyclerView.HORIZONTAL)
        // 进入分季行落到当前季，进入剧集行落到选中的那集
        binding.seasons.preferredPosition = { seasons.indexOfFirst { it.id == currentSeasonId } }
        binding.episodes.preferredPosition = { selectedEpisode?.id?.let { episodesAdapter?.indexOf(it) } ?: -1 }
    }

    override fun onResume() {
        super.onResume()
        if (firstLoad) open(intent.getStringExtra(EXTRA_ID)!!) else refresh()
    }

    // ---- 首次加载 ----

    /** 打开任意条目：剧集和季都换算成“所属剧 + 选中的季/集” */
    private fun open(id: String) {
        binding.state.showLoading()
        lifecycleScope.launch {
            val app = LumyApp.instance
            val entry = attempt { app.withReconnect { it.detail(id) } }.getOrElse {
                binding.state.showMessage(getString(R.string.load_failed, it.readableMessage()), getString(R.string.retry)) { open(id) }
                return@launch
            }
            app.currentServer()?.let { server = it }
            val item = entry.item
            val showId = when (item.type) {
                MediaType.EPISODE -> item.grandparentId
                MediaType.SEASON -> item.parentId
                else -> null
            }
            main = if (showId != null) attempt { server.detail(showId) }.getOrNull() ?: entry else entry
            when (item.type) {
                MediaType.EPISODE -> {
                    episodeDetails[item.id] = entry
                    selectedEpisode = item
                    currentSeasonId = item.parentId
                }
                MediaType.SEASON -> currentSeasonId = item.id
                else -> Unit
            }
            binding.state.hide()
            renderStatic(main!!)
            renderHeader()
            binding.actions.getChildAt(0)?.requestFocus()
            renderExtras(main!!)
            if (isSeries) {
                // 季和剧集还在加载：先放占位卡片，下方不会空着直接露出演员行
                showEpisodePlaceholders()
                loadSeasons()
            }
            firstLoad = false
        }
    }

    /** 背景、海报：剧页面始终用剧的图，切换剧集时不跳动 */
    private fun renderStatic(d: MediaDetail) {
        val dm = resources.displayMetrics
        d.item.backdrop?.let { lifecycleScope.launch { Backdrop.showAsWindowBackground(this@DetailActivity, server.imageUrl(it, dm.widthPixels, dm.heightPixels)) } }
        val lp = binding.posterFrame.layoutParams
        d.item.poster?.let { binding.poster.load(server.imageUrl(it, lp.width, lp.height)) }
    }

    /** 从播放器返回：刷新进度和已看状态，保留焦点和选中 */
    private fun refresh() {
        lifecycleScope.launch {
            LumyApp.instance.awaitPendingReports()
            main?.item?.id?.let { id -> attempt { server.detail(id) }.getOrNull()?.let { main = it } }
            selectedEpisode?.id?.let { id ->
                attempt { server.detail(id) }.getOrNull()?.let {
                    episodeDetails[id] = it
                    selectedEpisode = it.item
                }
            }
            renderHeader()
            if (focusPlayOnReturn) {
                focusPlayOnReturn = false
                binding.actions.getChildAt(0)?.requestFocus()
            }
            currentSeasonId?.let { refreshEpisodes(it) }
        }
    }

    // ---- 上方详情 ----

    private val headerItem: MediaItem? get() = selectedEpisode ?: main?.item
    private val headerDetail: MediaDetail?
        get() = selectedEpisode?.let { episodeDetails[it.id] } ?: main.takeIf { selectedEpisode == null }

    private fun renderHeader() {
        val item = headerItem ?: return
        val d = headerDetail
        val showDetail = main
        val isEpisode = item.type == MediaType.EPISODE

        // 剧页面上始终占位，选中剧集前后高度一致
        binding.parentTitle.visibility = when {
            isEpisode -> View.VISIBLE
            isSeries -> View.INVISIBLE
            else -> View.GONE
        }
        binding.parentTitle.text = listOfNotNull(
            item.showTitle ?: showDetail?.item?.title,
            item.seasonNumber?.let { s -> item.episodeNumber?.let { e -> getString(R.string.episode_code, s, e) } },
        ).joinToString("  ·  ")
        binding.title.text = item.title
        binding.meta.text = listOfNotNull(
            (item.year ?: showDetail?.item?.year)?.toString(),
            (d ?: showDetail)?.contentRating,
            item.durationMs?.takeIf { item.type != MediaType.SHOW }?.let { formatDuration(this, it) },
            d?.rating?.let { getString(R.string.rating, it) },
            (d?.genres?.takeIf { it.isNotEmpty() } ?: showDetail?.genres.orEmpty()).take(3).joinToString(" / ").ifEmpty { null },
        ).joinToString("  ·  ")
        val tagline = d?.tagline.takeIf { !isEpisode && !isSeries }
        binding.tagline.text = tagline
        binding.tagline.visibility = if (tagline.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.summary.text = item.summary
        binding.summary.isFocusable = !item.summary.isNullOrBlank()
        val credits = d ?: showDetail
        binding.credits.text = listOfNotNull(
            credits?.directors?.take(2)?.joinToString("、")?.ifEmpty { null }?.let { getString(R.string.director, it) },
            credits?.cast?.take(4)?.joinToString("、")?.ifEmpty { null }?.let { getString(R.string.cast, it) },
        ).joinToString("    ")

        val versions = d?.versions.orEmpty()
        versionIndex = versionIndex.coerceIn(0, (versions.size - 1).coerceAtLeast(0))
        renderBadges(versions.getOrNull(versionIndex))
        fileInfo.render(versions.getOrNull(versionIndex))
        renderActions(item, d)
    }

    private fun renderBadges(version: MediaVersion?) {
        binding.badges.removeAllViews()
        val v = version ?: return
        listOfNotNull(
            v.video?.resolutionLabel,
            v.video?.hdr?.label,
            v.video?.codec?.uppercase(),
            v.audio.firstOrNull { it.selected }?.let { a -> listOfNotNull(a.codec.uppercase(), a.channelsLabel).joinToString(" ") },
        ).forEach { binding.badges.addBadge(it) }
    }

    /** 重建按钮时，焦点原本在按钮上就留在同一位置；在下方列表里就不打扰 */
    private fun renderActions(item: MediaItem, d: MediaDetail?) {
        val focused = binding.actions.indexOfChild(currentFocus)
        binding.actions.removeAllViews()
        if (item.type == MediaType.SHOW) {
            main?.onDeck?.let { next ->
                val label = if (next.seasonNumber != null && next.episodeNumber != null) {
                    getString(R.string.action_play_episode, next.seasonNumber!!, next.episodeNumber!!)
                } else {
                    getString(R.string.action_play)
                }
                binding.actions.addActionButton(R.drawable.ic_play, label) { play(next, next.viewOffsetMs ?: 0) }
            }
        } else {
            val resume = item.viewOffsetMs?.takeIf { it > 30_000 }
            if (resume != null) {
                binding.actions.addActionButton(R.drawable.ic_play, getString(R.string.action_resume, formatClock(resume))) { play(item, resume) }
                binding.actions.addActionButton(R.drawable.ic_replay, getString(R.string.action_restart)) { play(item, 0) }
            } else {
                binding.actions.addActionButton(R.drawable.ic_play, getString(R.string.action_play)) { play(item, 0) }
            }
            if ((d?.versions?.size ?: 0) > 1) {
                binding.actions.addActionButton(R.drawable.ic_layers, getString(R.string.action_version)) { pickVersion(d!!) }
            }
        }
        val (icon, label) = if (item.watched) R.drawable.ic_circle to R.string.mark_unwatched else R.drawable.ic_check to R.string.mark_watched
        binding.actions.addActionButton(icon, getString(label)) {
            ItemMenu.toggleWatched(this, server, item) { afterWatchedChange() }
        }
        if (focused >= 0) binding.actions.getChildAt(focused.coerceAtMost(binding.actions.childCount - 1))?.requestFocus()
    }

    private fun afterWatchedChange() {
        episodeDetails.clear()
        refresh()
        if (isSeries) lifecycleScope.launch {
            val fresh = attempt { server.children(main!!.item.id) }.getOrNull() ?: return@launch
            seasons = fresh
            updateTabStates()
        }
    }

    private fun pickVersion(d: MediaDetail) {
        val options = d.versions.mapIndexed { i, v ->
            Option(v.summary, v.bitrateKbps?.let { "%.1f Mbps · %s".format(it / 1000f, v.container.orEmpty().uppercase()) }, i == versionIndex)
        }
        OptionsPanel(this, getString(R.string.version_title), options) { i ->
            versionIndex = i
            renderBadges(d.versions[i])
            fileInfo.render(d.versions[i])
        }.show()
    }

    /** 从播放器回来时把焦点放到播放按钮上（不论是从按钮还是从剧集卡片开始播放的） */
    private var focusPlayOnReturn = false

    private fun play(item: MediaItem, startMs: Long) {
        focusPlayOnReturn = true
        val version = if (item.id == headerItem?.id) versionIndex else 0
        startActivity(PlayerActivity.intent(this, item.id, version, startMs))
    }

    // ---- 分季标签 ----

    private fun loadSeasons() {
        val show = main ?: return
        lifecycleScope.launch {
            seasons = attempt { server.children(show.item.id) }.getOrDefault(emptyList())
            if (seasons.isEmpty()) return@launch
            val initial = seasons.firstOrNull { it.id == currentSeasonId }
                ?: seasons.firstOrNull { it.id == show.onDeck?.parentId }
                ?: seasons.first()
            currentSeasonId = initial.id
            showEpisodePlaceholders()
            binding.seasons.visibility = View.VISIBLE
            binding.seasons.adapter = SeasonTabAdapter()
            binding.seasons.scrollToPosition(seasons.indexOf(initial))
            loadEpisodes(initial.id, scrollTo = selectedEpisode?.id ?: show.onDeck?.id)
        }
    }

    /**
     * 焦点在标签上停留 250 毫秒才切换：快速划过不会每个都请求，
     * 焦点被转到当前季之前短暂经过的标签也不会误触发。
     */
    private fun onSeasonFocused(season: MediaItem, tab: View) {
        seasonJob?.cancel()
        if (season.id == currentSeasonId) return
        seasonJob = lifecycleScope.launch {
            delay(SEASON_SWITCH_DELAY_MS)
            if (tab.hasFocus() || tab.isFocused) switchSeason(season)
        }
    }

    private fun switchSeason(season: MediaItem) {
        if (season.id == currentSeasonId) return
        currentSeasonId = season.id
        updateTabStates()
        showEpisodePlaceholders()
        loadEpisodes(season.id, scrollTo = null)
    }

    /** 只改高亮状态，不刷新整行：刷新会重建有焦点的标签，焦点会丢到页面顶部 */
    private fun updateTabStates() {
        for (i in 0 until binding.seasons.childCount) {
            val child = binding.seasons.getChildAt(i)
            val position = binding.seasons.getChildAdapterPosition(child)
            child.isActivated = seasons.getOrNull(position)?.id == currentSeasonId
        }
    }

    /** 只显示季标题；多于一季时末尾有“查看全部”，用大卡片挑季 */
    private inner class SeasonTabAdapter : RecyclerView.Adapter<SeasonTabAdapter.Holder>() {
        private val hasViewAll get() = seasons.size > 1

        override fun getItemCount() = seasons.size + if (hasViewAll) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_season_tab, parent, false) as TextView)

        override fun onBindViewHolder(holder: Holder, position: Int) {
            if (position == seasons.size) {
                holder.view.text = getString(R.string.seasons_all)
                holder.view.isActivated = false
                holder.view.onFocusChangeListener = null
                holder.view.setOnLongClickListener(null)
                holder.view.setOnKeyListener(null)
                holder.view.setOnClickListener {
                    val show = main?.item ?: return@setOnClickListener
                    seasonPicker.launch(ItemListActivity.pickSeason(this@DetailActivity, show.id, show.title, currentSeasonId))
                }
                return
            }
            val season = seasons[position]
            holder.view.text = season.title.ifBlank { season.seasonNumber?.let { getString(R.string.season_n, it) }.orEmpty() }
            holder.view.isActivated = season.id == currentSeasonId
            holder.view.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) onSeasonFocused(season, v) }
            holder.view.setOnClickListener { seasonJob?.cancel(); switchSeason(season) }
            holder.view.setOnLongClickListener { showCardMenu(season); true }
            holder.view.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_MENU && event.action == android.view.KeyEvent.ACTION_UP) showCardMenu(season)
                keyCode == android.view.KeyEvent.KEYCODE_MENU
            }
        }

        inner class Holder(val view: TextView) : RecyclerView.ViewHolder(view)
    }

    // ---- 剧集列表 ----

    private fun episodesAdapter(): CardAdapter = episodesAdapter ?: CardAdapter(
        server, HubStyle.LANDSCAPE,
        onFocused = ::selectEpisode,
        onClick = ::onEpisodeClicked,
        onLongClick = ::showCardMenu,
    ).also {
        episodesAdapter = it
        binding.episodes.adapter = it
    }

    /** 剧集还没加载出来时先放几张灰色占位卡片，行高不变，焦点也不会直接跳到演员行 */
    private fun showEpisodePlaceholders() {
        binding.episodes.visibility = View.VISIBLE
        val adapter = episodesAdapter()
        val hadFocus = binding.episodes.hasFocus()
        adapter.resize(PLACEHOLDER_COUNT)
        if (hadFocus) binding.episodes.post { binding.episodes.getChildAt(0)?.requestFocus() }
    }

    private fun loadEpisodes(seasonId: String, scrollTo: String?) {
        lifecycleScope.launch {
            val episodes = attempt { server.children(seasonId) }.getOrDefault(emptyList())
            if (seasonId != currentSeasonId) return@launch
            binding.episodes.visibility = View.VISIBLE
            val adapter = episodesAdapter()
            adapter.selectedId = selectedEpisode?.id
            adapter.submit(episodes)
            // 定位到选中的那集，其次是第一集没看完的
            val target = scrollTo?.let { id -> episodes.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
                ?: episodes.indexOfFirst { !it.watched }.coerceAtLeast(0)
            (binding.episodes.layoutManager as StartAlignedLayoutManager).scrollToPositionWithOffset(target, 0)
        }
    }

    private fun refreshEpisodes(seasonId: String) {
        lifecycleScope.launch {
            attempt { server.children(seasonId) }.getOrNull()?.let { episodesAdapter?.submit(it) }
        }
    }

    /**
     * 遥控器：焦点移到哪集就选中哪集，上方立即换成这集（文字来自列表，格式等稍后补全）。
     * 鼠标点击不会移动焦点，所以点击未选中的集也是选中，点击已选中的才播放。
     */
    private fun selectEpisode(episode: MediaItem) {
        if (episode.id == selectedEpisode?.id) return
        selectedEpisode = episode
        versionIndex = 0
        episodesAdapter?.selectedId = episode.id
        // 上方详情的重新布局要几十毫秒，和卡片放大动画挤在同一帧会掉帧（看起来闪一下）。
        // 等放大动画结束再更新；快速连续切换时只更新最后停下的那一集
        headerJob?.cancel()
        headerJob = lifecycleScope.launch {
            delay(HEADER_UPDATE_DELAY_MS)
            renderHeader()
        }
        if (episodeDetails.containsKey(episode.id)) return
        episodeDetailJob?.cancel()
        episodeDetailJob = lifecycleScope.launch {
            delay(EPISODE_DETAIL_DELAY_MS)
            val d = attempt { server.detail(episode.id) }.getOrNull() ?: return@launch
            episodeDetails[episode.id] = d
            if (selectedEpisode?.id == episode.id) renderHeader()
        }
    }

    private fun onEpisodeClicked(episode: MediaItem) {
        if (episode.id != selectedEpisode?.id) {
            selectEpisode(episode)
        } else {
            play(episode, episode.viewOffsetMs?.takeIf { it > 30_000 } ?: 0)
        }
    }

    // ---- 演员、更多类似 ----

    private fun renderExtras(d: MediaDetail) {
        val settings = LumyApp.instance.settings
        if (settings.detailShowCast && d.people.isNotEmpty()) {
            binding.castTitle.visibility = View.VISIBLE
            binding.cast.visibility = View.VISIBLE
            binding.cast.adapter = CardAdapter(server, HubStyle.PERSON, onFocused = {}, onClick = { Navigator.open(this, it, d.libraryId) })
                .also { it.submit(d.people.take(MAX_PEOPLE)) }
        }
        // 关闭时连请求都不发
        if (!settings.detailShowSimilar || (d.item.type != MediaType.MOVIE && d.item.type != MediaType.SHOW)) return
        lifecycleScope.launch {
            val similar = attempt { server.similar(d.item.id) }.getOrDefault(emptyList())
            if (similar.isEmpty()) return@launch
            binding.similarTitle.visibility = View.VISIBLE
            binding.similar.visibility = View.VISIBLE
            binding.similar.adapter = CardAdapter(server, HubStyle.POSTER, onFocused = {}, onClick = { Navigator.open(this@DetailActivity, it) }, onLongClick = ::showCardMenu)
                .also { it.submit(similar) }
        }
    }

    private fun showCardMenu(card: MediaItem) {
        ItemMenu.show(this, server, card) { afterWatchedChange() }
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val MAX_PEOPLE = 20
        private const val SEASON_SWITCH_DELAY_MS = 250L
        private const val EPISODE_DETAIL_DELAY_MS = 300L
        private const val PLACEHOLDER_COUNT = 6
        private const val HEADER_UPDATE_DELAY_MS = 160L

        fun intent(context: Context, id: String, seasonId: String? = null): Intent =
            Intent(context, DetailActivity::class.java).putExtra(EXTRA_ID, seasonId ?: id)
    }
}

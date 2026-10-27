package com.hesier.lumy.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.hesier.lumy.ui.ThemedActivity
import androidx.core.view.children
import androidx.lifecycle.lifecycleScope
import com.hesier.lumy.ui.widget.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.core.TrackLanguage
import com.hesier.lumy.BuildConfig
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.data.AppLanguage
import com.hesier.lumy.data.Settings
import com.hesier.lumy.data.SkipMode
import com.hesier.lumy.data.SubtitleMode
import com.hesier.lumy.data.ThemeMode
import com.hesier.lumy.data.VideoScale
import com.hesier.lumy.databinding.ActivitySettingsBinding
import com.hesier.lumy.databinding.ItemSettingBinding
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.data.SavedServer
import com.hesier.lumy.ui.detail
import com.hesier.lumy.ui.icon
import com.hesier.lumy.ui.home.HomeActivity
import com.hesier.lumy.ui.servers.AddServerActivity
import com.hesier.lumy.ui.player.Quality
import com.hesier.lumy.ui.player.qualityLabel
import com.hesier.lumy.ui.player.videoScaleLabel
import com.hesier.lumy.ui.servers.ServersActivity
import com.hesier.lumy.ui.widget.InputPanel
import com.hesier.lumy.ui.widget.Option
import com.hesier.lumy.ui.widget.OptionsPanel
import com.hesier.lumy.ui.widget.ReorderPanel
import com.hesier.lumy.ui.widget.StepperPanel
import com.hesier.lumy.ui.widget.TextPanel
import com.hesier.lumy.ui.player.formatDelay

class SettingsActivity : ThemedActivity() {

    private data class Row(val label: String, val value: String, val detail: String? = null, val icon: Int? = null, val onClick: () -> Unit)

    private lateinit var binding: ActivitySettingsBinding
    private val app get() = LumyApp.instance
    private val settings: Settings get() = app.settings
    private val adapter = Adapter()
    private val categoryAdapter = CategoryAdapter()
    /** 每个分类的条目，下标对应 [CATEGORIES] */
    private var sections: List<List<Row>> = emptyList()
    private var category = 0
    /** 每个分类右侧上次聚焦的行，切回来时焦点落在原处 */
    private val lastFocused = IntArray(CATEGORIES.size)
    /** 恢复焦点之前系统会先把焦点给第一个分类，这时不能跟着切换分类 */
    private var focusRestored = false
    /** 侧边栏设置针对当前服务器 */
    private val entryId: String get() = app.currentEntry?.id.orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.categories.layoutManager = LinearLayoutManager(this)
        binding.categories.adapter = categoryAdapter
        binding.categories.keepVerticalFocus = true
        // 从右侧按左键回到当前分类，而不是几何上最近的那一项
        binding.categories.preferredPosition = { category }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.list.keepVerticalFocus = true
        binding.list.preferredPosition = { lastFocused[category] }
        // 换主题重建后回到原来的分类和那一行
        savedInstanceState?.let {
            category = it.getInt(KEY_CATEGORY)
            it.getIntArray(KEY_LAST_FOCUSED)?.copyInto(lastFocused)
        }
        render()
        val inList = savedInstanceState?.getBoolean(KEY_IN_LIST) == true
        val target = if (inList) binding.list else binding.categories
        val position = if (inList) lastFocused[category] else category
        target.scrollToPosition(position)
        target.post {
            focusRestored = true
            target.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CATEGORY, category)
        outState.putIntArray(KEY_LAST_FOCUSED, lastFocused)
        outState.putBoolean(KEY_IN_LIST, binding.list.hasFocus())
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 在右侧按返回先回到左侧分类，在左侧再按才退出
        if (event.keyCode == KeyEvent.KEYCODE_BACK && binding.list.hasFocus()) {
            if (event.action == KeyEvent.ACTION_UP) binding.categories.requestFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun selectCategory(index: Int) {
        if (index == category || index !in CATEGORIES.indices) return
        category = index
        for (child in binding.categories.children) child.isActivated = binding.categories.getChildAdapterPosition(child) == category
        binding.list.scrollToPosition(lastFocused[category])
        showRows()
    }

    /** 刷新右侧；行数变了（删了服务器）时 notifyDataSetChanged 会丢焦点，要放回去 */
    private fun showRows() {
        val rows = sections[category]
        val hadFocus = binding.list.hasFocus()
        val sameShape = adapter.rows.size == rows.size
        lastFocused[category] = lastFocused[category].coerceIn(0, rows.lastIndex)
        adapter.rows = rows
        if (hadFocus && !sameShape) {
            binding.list.post { binding.list.findViewHolderForAdapterPosition(lastFocused[category])?.itemView?.requestFocus() }
        }
    }

    private fun render() {
        val subtitleLabels = listOf(R.string.subtitle_size_small, R.string.subtitle_size_normal, R.string.subtitle_size_large, R.string.subtitle_size_xlarge)
            .map(::getString)
        // 找不到（旧版本存的值）时显示“标准”；不能用 coerceAtLeast(1)，那样“小”（下标 0）也会变成“标准”
        val scaleIndex = Settings.SUBTITLE_SCALES.indexOf(settings.subtitleScale).takeIf { it >= 0 } ?: 1
        val positionLabels = listOf(R.string.subtitle_position_low, R.string.subtitle_position_normal, R.string.subtitle_position_high, R.string.subtitle_position_higher)
            .map(::getString)
        sections = listOf(
            listOf(
            Row(getString(R.string.settings_language), languageLabel(settings.language)) {
                val languages = AppLanguage.entries
                OptionsPanel(this, getString(R.string.settings_language), languages.map { Option(languageLabel(it), selected = it == settings.language) }) {
                    if (languages[it] == settings.language) return@OptionsPanel
                    app.setLanguage(languages[it])
                    // 和换主题一样：当前页面立即重建，返回栈里的页面回到前台时重建
                    recreate()
                }.show()
            },
            Row(getString(R.string.settings_theme), themeLabel(settings.theme), getString(R.string.settings_theme_detail)) {
                val modes = ThemeMode.entries
                OptionsPanel(this, getString(R.string.settings_theme), modes.map { Option(themeLabel(it), selected = it == settings.theme) }) {
                    if (modes[it] == settings.theme) return@OptionsPanel
                    settings.theme = modes[it]
                    // 当前页面立即重建；返回栈里的页面回到前台时由 ThemedActivity 重建
                    recreate()
                }.show()
            },
            Row(getString(R.string.settings_ui_scale), uiScaleLabel(settings.uiScale), getString(R.string.settings_ui_scale_detail)) {
                val scales = Settings.UI_SCALES
                OptionsPanel(this, getString(R.string.settings_ui_scale), scales.map { Option(uiScaleLabel(it), selected = it == settings.uiScale) }) {
                    if (scales[it] == settings.uiScale) return@OptionsPanel
                    settings.uiScale = scales[it]
                    // 和换主题一样：当前页面立即重建，返回栈里的页面回到前台时重建
                    recreate()
                }.show()
            },
            Row(getString(R.string.settings_sidebar), sidebarSummary(), getString(R.string.settings_sidebar_detail)) { pickSidebarItems() },
            Row(getString(R.string.settings_sidebar_order), "", getString(R.string.settings_sidebar_order_detail)) { reorderSidebar() },
            Row(getString(R.string.settings_detail_cast), onOff(settings.detailShowCast)) {
                settings.detailShowCast = !settings.detailShowCast
                render()
            },
            Row(getString(R.string.settings_detail_similar), onOff(settings.detailShowSimilar)) {
                settings.detailShowSimilar = !settings.detailShowSimilar
                render()
            },
            Row(getString(R.string.settings_detail_file_info), onOff(settings.detailShowFileInfo)) {
                settings.detailShowFileInfo = !settings.detailShowFileInfo
                render()
            },
            ),
            listOf(
            Row(getString(R.string.settings_default_quality), qualityLabel(this, Quality.presets[settings.defaultQuality.coerceIn(0, Quality.presets.lastIndex)])) {
                choose(R.string.settings_default_quality, Quality.presets.map { qualityLabel(this, it) }, settings.defaultQuality) { settings.defaultQuality = it }
            },
            Row(getString(R.string.settings_passthrough), onOff(settings.passthrough), getString(R.string.settings_passthrough_detail)) {
                settings.passthrough = !settings.passthrough
                render()
            },
            Row(getString(R.string.settings_allow_transcode), onOff(settings.allowTranscode), getString(R.string.settings_allow_transcode_detail)) {
                settings.allowTranscode = !settings.allowTranscode
                render()
            },
            Row(getString(R.string.settings_display_hdr), onOff(settings.displaySupportsHdr), getString(R.string.settings_display_hdr_detail)) {
                settings.displaySupportsHdr = !settings.displaySupportsHdr
                render()
            },
            Row(getString(R.string.settings_allow_burn), onOff(settings.allowSubtitleBurn), getString(R.string.settings_allow_burn_detail)) {
                settings.allowSubtitleBurn = !settings.allowSubtitleBurn
                render()
            },
            Row(getString(R.string.settings_video_scale), videoScaleLabel(this, settings.videoScale), getString(R.string.settings_video_scale_detail)) {
                val scales = VideoScale.entries
                choose(R.string.settings_video_scale, scales.map { videoScaleLabel(this, it) }, scales.indexOf(settings.videoScale)) { settings.videoScale = scales[it] }
            },
            Row(getString(R.string.settings_frame_rate), onOff(settings.matchFrameRate), getString(R.string.settings_frame_rate_detail)) {
                settings.matchFrameRate = !settings.matchFrameRate
                render()
            },
            Row(getString(R.string.settings_audio_delay), formatDelay(this, settings.audioDelayMs), getString(R.string.settings_audio_delay_detail)) {
                StepperPanel(
                    this, getString(R.string.settings_audio_delay), getString(R.string.delay_hint), settings.audioDelayMs,
                    100, 1_000, -5_000L..5_000L, { formatDelay(this, it) },
                ) { settings.audioDelayMs = it }.apply { setOnDismissListener { render() } }.show()
            },
            Row(getString(R.string.settings_audio_language), audioLanguageLabel(), getString(R.string.settings_audio_language_detail)) {
                val codes = listOf(null) + AUDIO_LANGUAGES.map { it.first }
                choose(R.string.settings_audio_language, listOf(getString(R.string.follow_server)) + AUDIO_LANGUAGES.map { getString(it.second) }, codes.indexOf(settings.audioLanguage).coerceAtLeast(0)) {
                    settings.audioLanguage = codes[it]
                }
            },
            Row(getString(R.string.settings_auto_next), onOff(settings.autoPlayNext)) {
                settings.autoPlayNext = !settings.autoPlayNext
                render()
            },
            Row(getString(R.string.settings_skip_intro), skipLabel(settings.skipIntro)) {
                chooseSkip(R.string.settings_skip_intro, settings.skipIntro) { settings.skipIntro = it }
            },
            Row(getString(R.string.settings_skip_credits), skipLabel(settings.skipCredits)) {
                chooseSkip(R.string.settings_skip_credits, settings.skipCredits) { settings.skipCredits = it }
            },
            ),
            listOf(
            Row(getString(R.string.settings_subtitle_size), subtitleLabels[scaleIndex]) {
                choose(R.string.settings_subtitle_size, subtitleLabels, scaleIndex) { settings.subtitleScale = Settings.SUBTITLE_SCALES[it] }
            },
            Row(getString(R.string.settings_subtitle_position), positionLabels[settings.subtitlePosition], getString(R.string.settings_subtitle_position_detail)) {
                choose(R.string.settings_subtitle_position, positionLabels, settings.subtitlePosition) { settings.subtitlePosition = it }
            },
            Row(
                getString(R.string.settings_subtitle_background), onOff(settings.subtitleBackground),
                getString(R.string.settings_subtitle_background_detail) + "\n" + getString(R.string.settings_subtitle_style_note),
            ) {
                settings.subtitleBackground = !settings.subtitleBackground
                render()
            },
            Row(getString(R.string.settings_subtitle_mode), subtitleModeLabel(settings.subtitleMode), getString(R.string.settings_subtitle_mode_detail)) {
                val modes = SubtitleMode.entries
                choose(R.string.settings_subtitle_mode, modes.map(::subtitleModeLabel), modes.indexOf(settings.subtitleMode)) { settings.subtitleMode = modes[it] }
            },
            Row(getString(R.string.settings_subtitle_language), subtitleLanguageLabel(), getString(R.string.settings_subtitle_language_detail)) {
                val codes = SUBTITLE_LANGUAGES.map { it.first }
                choose(R.string.settings_subtitle_language, SUBTITLE_LANGUAGES.map { getString(it.second) }, codes.indexOf(settings.subtitleLanguage).coerceAtLeast(0)) {
                    settings.subtitleLanguage = codes[it]
                }
            },
            Row(getString(R.string.settings_clear_track_memory), "", getString(R.string.settings_clear_track_memory_detail)) {
                app.trackMemory.clear()
                Toast.makeText(this, R.string.track_memory_cleared, Toast.LENGTH_SHORT).show()
            },
            ),
            serverRows() + Row(getString(R.string.settings_add_server), "", getString(R.string.settings_add_server_detail)) {
                startActivity(AddServerActivity.intent(this))
            },
            listOf(
                Row(getString(R.string.settings_version), BuildConfig.VERSION_NAME) {},
                Row(getString(R.string.device_info), "", getString(R.string.device_info_detail)) { showDeviceInfo() },
            ),
        )
        showRows()
    }

    override fun onRestart() {
        super.onRestart()
        // 从添加服务器的页面回来，列表可能变了
        render()
    }

    private fun serverRows(): List<Row> {
        val currentId = app.currentEntry?.id
        return app.store.servers.map { s ->
            val current = s.id == currentId
            Row(s.displayName, if (current) getString(R.string.server_current) else "", s.detail(this), s.kind.icon) { manageServer(s, current) }
        }
    }

    /** 服务器的操作：切换过去、（Plex）在账号下重新选择、重命名、删除 */
    private fun manageServer(server: SavedServer, current: Boolean) {
        val actions = buildList<Pair<String, () -> Unit>> {
            if (!current) {
                add(getString(R.string.server_switch) to {
                    app.switchTo(server.id)
                    HomeActivity.restart(this@SettingsActivity)
                })
            }
            val token = server.accountToken
            if (server.kind == ServerKind.PLEX && token != null) {
                add(getString(R.string.server_repick) to {
                    startActivity(ServersActivity.intent(this@SettingsActivity, token, replaceId = server.id))
                })
            }
            add(getString(R.string.server_rename) to { renameServer(server) })
            add(getString(R.string.server_remove) to { confirmRemove(server) })
        }
        OptionsPanel(this, server.displayName, actions.map { Option(it.first) }) { i -> actions[i].second() }.show()
    }

    /** 留空保存则恢复服务端返回的名称；改的是当前服务器时首页回来会按列表变化重建，侧边栏的名字跟着更新 */
    private fun renameServer(server: SavedServer) {
        InputPanel(this, getString(R.string.server_rename), server.customName.orEmpty(), server.session.name, getString(R.string.server_rename_save)) {
            app.renameServer(server.id, it)
            render()
        }.show()
    }

    /** 删除要再确认一次，默认焦点在“取消”上，避免遥控器误触 */
    private fun confirmRemove(server: SavedServer) {
        val options = listOf(Option(getString(R.string.cancel), selected = true), Option(getString(R.string.server_remove_confirm)))
        OptionsPanel(this, getString(R.string.server_remove), options) { i ->
            if (i != 1) return@OptionsPanel
            val wasCurrent = app.currentEntry?.id == server.id
            app.removeServer(server.id)
            // 删的是当前服务器（或删完了）就重新打开首页：会换到下一台，或者进入添加服务器
            if (wasCurrent) HomeActivity.restart(this) else render()
        }.show()
    }

    private var sidebarCandidates: List<Pair<String, String>> = emptyList()

    private fun sidebarSummary(): String {
        val hidden = settings.hiddenSidebarItems(entryId)
        if (hidden.isEmpty()) return getString(R.string.sidebar_all)
        val total = sidebarCandidates.size.takeIf { it > 0 } ?: return resources.getQuantityString(R.plurals.sidebar_hidden_n, hidden.size, hidden.size)
        return getString(R.string.sidebar_shown_n, total - sidebarCandidates.count { it.first in hidden }, total)
    }

    /** 列出媒体库和“播放列表”，勾选的显示在侧边栏 */
    private fun pickSidebarItems() {
        val server = app.currentServer() ?: return
        lifecycleScope.launch {
            val libraries = attempt { server.libraries() }.getOrDefault(emptyList())
            sidebarCandidates = libraries.map { it.id to it.title } + (Settings.PLAYLISTS_ID to getString(R.string.nav_playlists))
            val hidden = settings.hiddenSidebarItems(entryId).toMutableSet()
            val options = sidebarCandidates.map { Option(it.second, selected = it.first !in hidden) }
            OptionsPanel(this@SettingsActivity, getString(R.string.settings_sidebar), options, multi = true) { i ->
                val id = sidebarCandidates[i].first
                if (!hidden.add(id)) hidden.remove(id)
                settings.setHiddenSidebarItems(entryId, hidden.toSet())
            }.apply { setOnDismissListener { render() } }.show()
        }
    }

    private fun showDeviceInfo() {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.Default) { DeviceInfo.build(this@SettingsActivity) }
            TextPanel(this@SettingsActivity, getString(R.string.device_info), text).show()
        }
    }

    private fun reorderSidebar() {
        val server = app.currentServer() ?: return
        lifecycleScope.launch {
            val libraries = attempt { server.libraries() }.getOrDefault(emptyList())
            val ordered = Settings.applyOrder(libraries, settings.sidebarOrder(entryId)) { it.id }
            val hidden = settings.hiddenSidebarItems(entryId)
            val visible = ordered.filterNot { it.id in hidden }
            ReorderPanel(this@SettingsActivity, getString(R.string.settings_sidebar_order), visible.map { it.id to it.title }) { ids ->
                // 隐藏的库不参与排序但留在原位，显示中的库按新顺序填回原来的位置；取消隐藏后回到原处
                val next = ids.iterator()
                settings.setSidebarOrder(entryId, ordered.map { if (it.id in hidden) it.id else next.next() })
            }.show()
        }
    }

    private fun audioLanguageLabel() = AUDIO_LANGUAGES.firstOrNull { it.first == settings.audioLanguage }
        ?.let { getString(it.second) } ?: getString(R.string.follow_server)

    private fun subtitleLanguageLabel() = SUBTITLE_LANGUAGES.firstOrNull { it.first == settings.subtitleLanguage }
        ?.let { getString(it.second) } ?: settings.subtitleLanguage

    private fun subtitleModeLabel(mode: SubtitleMode) = getString(
        when (mode) {
            SubtitleMode.SERVER -> R.string.follow_server
            SubtitleMode.LANGUAGE -> R.string.subtitle_mode_language
            SubtitleMode.FORCED_ONLY -> R.string.subtitle_mode_forced
            SubtitleMode.OFF -> R.string.subtitle_mode_off
        },
    )

    /** 各语言用自己的文字显示，选错了也认得出来 */
    private fun languageLabel(language: AppLanguage) = getString(
        when (language) {
            AppLanguage.SYSTEM -> R.string.theme_system
            AppLanguage.ENGLISH -> R.string.language_english
            AppLanguage.SIMPLIFIED_CHINESE -> R.string.language_zh_hans
            AppLanguage.TRADITIONAL_CHINESE -> R.string.language_zh_hant
        },
    )

    private fun uiScaleLabel(scale: Float) =
        if (scale == 1f) getString(R.string.ui_scale_default) else getString(R.string.ui_scale_percent, (scale * 100).roundToInt())

    private fun themeLabel(mode: ThemeMode) = getString(
        when (mode) {
            ThemeMode.DARK -> R.string.theme_dark
            ThemeMode.LIGHT -> R.string.theme_light
            ThemeMode.SYSTEM -> R.string.theme_system
        },
    )

    private fun onOff(v: Boolean) = getString(if (v) R.string.on else R.string.off)

    private fun skipLabel(mode: SkipMode) = getString(
        when (mode) {
            SkipMode.OFF -> R.string.skip_off
            SkipMode.BUTTON -> R.string.skip_button
            SkipMode.AUTO -> R.string.skip_auto
        },
    )

    private fun chooseSkip(title: Int, current: SkipMode, save: (SkipMode) -> Unit) {
        val modes = SkipMode.entries
        choose(title, modes.map(::skipLabel), modes.indexOf(current)) { save(modes[it]) }
    }

    private fun choose(title: Int, labels: List<String>, selected: Int, save: (Int) -> Unit) {
        OptionsPanel(this, getString(title), labels.mapIndexed { i, l -> Option(l, selected = i == selected) }) {
            save(it)
            render()
        }.show()
    }

    private inner class Adapter : RecyclerView.Adapter<ItemHolder>() {
        var rows: List<Row> = emptyList()
            set(value) {
                val sameShape = field.size == value.size
                field = value
                // 行数不变时逐行刷新，焦点留在原处
                if (sameShape) notifyItemRangeChanged(0, value.size) else notifyDataSetChanged()
            }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemHolder {
            val holder = ItemHolder(ItemSettingBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && holder.bindingAdapterPosition >= 0) lastFocused[category] = holder.bindingAdapterPosition
            }
            return holder
        }

        override fun onBindViewHolder(holder: ItemHolder, position: Int) = holder.bind(rows[position])
    }

    private inner class CategoryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = CATEGORIES.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_setting_category, parent, false)
            val holder = object : RecyclerView.ViewHolder(view) {}
            // 上下移动即切换右侧内容，不用按确认
            view.setOnFocusChangeListener { _, hasFocus -> if (hasFocus && focusRestored) selectCategory(holder.bindingAdapterPosition) }
            // 遥控器按确认进入右侧；触屏点击不会移动焦点，上面的焦点监听不触发，要在这里切换分类
            view.setOnClickListener {
                if (view.isInTouchMode) selectCategory(holder.bindingAdapterPosition) else binding.list.requestFocus()
            }
            return holder
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as TextView).setText(CATEGORIES[position])
            holder.itemView.isActivated = position == category
        }
    }

    private class ItemHolder(private val b: ItemSettingBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: Row) {
            b.label.text = row.label
            b.value.text = row.value
            b.detail.text = row.detail
            b.detail.visibility = if (row.detail == null) View.GONE else View.VISIBLE
            b.icon.visibility = if (row.icon == null) View.GONE else View.VISIBLE
            row.icon?.let(b.icon::setImageResource)
            b.root.setOnClickListener { row.onClick() }
        }
    }

    private companion object {
        const val KEY_CATEGORY = "category"
        const val KEY_LAST_FOCUSED = "last_focused"
        const val KEY_IN_LIST = "in_list"
        /** 左侧分类，顺序与 [render] 里 sections 的顺序一致 */
        val CATEGORIES = listOf(
            R.string.settings_section_display,
            R.string.settings_section_playback,
            R.string.settings_section_subtitles,
            R.string.settings_section_servers,
            R.string.settings_section_about,
        )
        val AUDIO_LANGUAGES = listOf(
            TrackLanguage.ZH to R.string.lang_mandarin,
            TrackLanguage.YUE to R.string.lang_cantonese,
            "eng" to R.string.lang_english,
            "jpn" to R.string.lang_japanese,
            "kor" to R.string.lang_korean,
        )
        val SUBTITLE_LANGUAGES = listOf(
            TrackLanguage.ZH_HANS to R.string.lang_zh_hans,
            TrackLanguage.ZH_HANT to R.string.lang_zh_hant,
            "eng" to R.string.lang_english,
            "jpn" to R.string.lang_japanese,
            "kor" to R.string.lang_korean,
        )
    }
}

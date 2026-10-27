package com.hesier.lumy.ui.home

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.hesier.lumy.ui.ThemedActivity
import androidx.lifecycle.lifecycleScope
import com.hesier.lumy.core.Library
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.data.SavedServer
import com.hesier.lumy.data.Settings
import com.hesier.lumy.databinding.ActivityHomeBinding
import com.hesier.lumy.databinding.ItemSidebarBinding
import com.hesier.lumy.ui.Navigator
import com.hesier.lumy.ui.list.ItemListActivity
import com.hesier.lumy.ui.library.LibraryFragment
import com.hesier.lumy.ui.search.SearchFragment
import com.hesier.lumy.ui.servers.AddServerActivity
import com.hesier.lumy.ui.servers.ServersActivity
import com.hesier.lumy.ui.detail
import com.hesier.lumy.ui.widget.Option
import com.hesier.lumy.ui.widget.OptionsPanel
import com.hesier.lumy.ui.settings.SettingsActivity
import com.hesier.lumy.ui.widget.ItemMenu
import com.hesier.lumy.ui.widget.KeyRepeatThrottle
import com.hesier.lumy.ui.widget.Backdrop
import com.hesier.lumy.ui.widget.attempt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HomeActivity : ThemedActivity(), HomeHost {

    private lateinit var binding: ActivityHomeBinding
    override lateinit var server: MediaServer
        private set

    private val navItems = mutableListOf<NavItem>()
    /** 建侧边栏时的隐藏设置；从设置页回来发现变了就重建页面 */
    private var sidebarSnapshot: List<String>? = null

    /** 当前服务器的本地记录 id，侧边栏设置按它分开保存 */
    private lateinit var entryId: String
    /** 建页面时的服务器列表，决定顶部是否显示切换按钮 */
    private var serversSnapshot: List<SavedServer> = emptyList()

    override fun onRestart() {
        super.onRestart()
        val app = LumyApp.instance
        // 在设置里切换 / 删除了当前服务器
        if (app.currentEntry?.id != entryId) return restart(this)
        // 删除 / 添加了别的服务器：切换按钮的有无和列表都要更新
        if (app.store.servers != serversSnapshot) return recreate()
        val snapshot = sidebarSnapshot ?: return
        if (snapshot != sidebarState()) recreate()
    }

    private fun sidebarState(): List<String> {
        val settings = LumyApp.instance.settings
        return settings.hiddenSidebarItems(entryId).sorted() + "|" + settings.sidebarOrder(entryId)
    }
    private var sidebarExpanded = false
    private var sidebarAnimator: ValueAnimator? = null
    private var backdropJob: Job? = null
    private var backdropRef: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // 清单里是固定深色的启动主题，窗口创建前换回按设置取色的主题
        setTheme(R.style.Theme_Lumy)
        super.onCreate(savedInstanceState)
        val app = LumyApp.instance
        val current = app.currentServer()
        val entry = app.currentEntry
        if (current == null || entry == null) {
            // 首次启动（或服务器都删了）：先选择要添加的服务器类型
            startActivity(AddServerActivity.intent(this))
            finish()
            return
        }
        server = current
        entryId = entry.id
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSidebar()
        checkConnection()
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.content, HomeFragment()).commit()
        }
    }

    // ---- 侧边栏 ----

    private sealed interface NavTarget {
        data object Search : NavTarget
        data object Home : NavTarget
        data class Section(val library: Library) : NavTarget
    }

    private class NavItem(val target: NavTarget, val binding: ItemSidebarBinding)

    private fun setupSidebar() {
        setupServerSwitch()
        addNavItem(NavTarget.Search, R.drawable.ic_search, getString(R.string.nav_search))
        addNavItem(NavTarget.Home, R.drawable.ic_home, getString(R.string.nav_home))
        addFooterItem(R.drawable.ic_settings, getString(R.string.nav_settings)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.root.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            val inside = new != null && new.isInside(binding.sidebar)
            // 记住内容区最后获得焦点的卡片，从侧边栏按右键时回到它
            if (new != null && new.isInside(binding.content)) lastContentFocus = java.lang.ref.WeakReference(new)
            if (inside && (old == null || !old.isInside(binding.sidebar))) {
                // 从内容区进入侧边栏时，焦点落在当前页面对应的项上
                navItems.firstOrNull { it.binding.root.isActivated }?.binding?.root
                    ?.takeIf { it != new }?.let { it.post { it.requestFocus() } }
            }
            setSidebarExpanded(inside)
        }
        // 触屏点击不移动焦点，上面的焦点监听不会展开侧边栏，只能点图标
        binding.sidebarLogo.setOnClickListener { setSidebarExpanded(!sidebarExpanded) }
        lifecycleScope.launch {
            val loaded = attempt { server.libraries() }.getOrNull()
            val libraries = loaded ?: emptyList()
            val settings = LumyApp.instance.settings
            val hidden = settings.hiddenSidebarItems(entryId)
            sidebarSnapshot = sidebarState()
            Settings.applyOrder(libraries, settings.sidebarOrder(entryId)) { it.id }.filterNot { it.id in hidden }.forEach { lib ->
                val icon = if (lib.type == MediaType.SHOW) R.drawable.ic_tv else R.drawable.ic_movie
                addNavItem(NavTarget.Section(lib), icon, lib.title)
            }
            // 在设置里隐藏了当前所在的媒体库：重建后侧边栏没有对应项，回到首页。
            // 媒体库没加载出来时不算，免得断网时把用户踢回首页
            if (loaded != null && navItems.none { it.binding.root.isActivated }) navigate(NavTarget.Home)
            // 有视频播放列表才显示入口
            if (Settings.PLAYLISTS_ID !in hidden && attempt { server.playlists() }.getOrDefault(emptyList()).isNotEmpty()) {
                addFooterItem(R.drawable.ic_list, getString(R.string.nav_playlists), index = 0) {
                    startActivity(ItemListActivity.playlists(this@HomeActivity))
                }
            }
        }
    }

    /** 只有一台服务器时不显示；按下后列出所有服务器，选中的那台重新加载首页 */
    private fun setupServerSwitch() {
        val app = LumyApp.instance
        val servers = app.store.servers
        serversSnapshot = servers
        if (servers.size < 2) return
        val item = inflateSidebarItem(binding.sidebarHeader, R.drawable.ic_server, app.currentEntry?.displayName ?: server.session.name)
        (item.root.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = resources.getDimensionPixelSize(R.dimen.sidebar_group_gap)
        item.root.setOnClickListener {
            val options = servers.map { Option(it.displayName, it.detail(this), selected = it.id == entryId) }
            OptionsPanel(this, getString(R.string.nav_switch_server), options) { i ->
                val picked = servers[i]
                if (picked.id == entryId) return@OptionsPanel
                app.switchTo(picked.id)
                restart(this)
            }.show()
        }
    }

    private fun addNavItem(target: NavTarget, icon: Int, label: String): ItemSidebarBinding {
        val item = inflateSidebarItem(binding.sidebarItems, icon, label)
        navItems += NavItem(target, item)
        item.root.isActivated = isCurrentPage(target)
        item.root.setOnClickListener {
            collapseSidebarOnTouch()
            navigate(target)
        }
        return item
    }

    /** 换主题、缩放等重建页面后，FragmentManager 会恢复原来的内容页，侧边栏高亮要跟它一致 */
    private fun isCurrentPage(target: NavTarget): Boolean {
        val page = supportFragmentManager.findFragmentById(R.id.content) ?: return target == NavTarget.Home
        return when (target) {
            NavTarget.Search -> page is SearchFragment
            NavTarget.Home -> page is HomeFragment
            is NavTarget.Section -> (page as? LibraryFragment)?.libraryId == target.library.id
        }
    }

    private fun addFooterItem(icon: Int, label: String, index: Int = -1, onClick: () -> Unit) {
        val item = inflateSidebarItem(binding.sidebarFooter, icon, label)
        if (index >= 0) {
            binding.sidebarFooter.removeView(item.root)
            binding.sidebarFooter.addView(item.root, index)
        }
        item.root.setOnClickListener {
            collapseSidebarOnTouch()
            onClick()
        }
    }

    /** 触屏点了侧边栏里的项就收起；遥控器由焦点离开侧边栏时收起 */
    private fun collapseSidebarOnTouch() {
        if (binding.sidebar.isInTouchMode) setSidebarExpanded(false)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 侧边栏展开时点内容区只收起侧边栏，不点到下面的卡片
        if (sidebarExpanded && ev.actionMasked == MotionEvent.ACTION_DOWN && ev.x > binding.sidebar.width) {
            setSidebarExpanded(false)
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun inflateSidebarItem(parent: android.view.ViewGroup, icon: Int, label: String): ItemSidebarBinding {
        val item = ItemSidebarBinding.inflate(LayoutInflater.from(this), parent, true)
        item.icon.setImageResource(icon)
        item.label.text = label
        item.label.alpha = if (sidebarExpanded) 1f else 0f
        return item
    }

    private fun navigate(target: NavTarget) {
        navItems.forEach { it.binding.root.isActivated = it.target == target }
        val fragment: Fragment = when (target) {
            NavTarget.Search -> SearchFragment()
            NavTarget.Home -> HomeFragment()
            is NavTarget.Section -> LibraryFragment.newInstance(target.library)
        }
        showBackdrop(null)
        lastContentFocus = null
        supportFragmentManager.beginTransaction().replace(R.id.content, fragment).commit()
        binding.content.post { binding.content.requestFocus(View.FOCUS_RIGHT) }
    }

    private fun setSidebarExpanded(expanded: Boolean) {
        if (expanded == sidebarExpanded) return
        sidebarExpanded = expanded
        val res = resources
        val from = binding.sidebar.layoutParams.width
        val to = res.getDimensionPixelSize(if (expanded) R.dimen.sidebar_expanded else R.dimen.sidebar_collapsed)
        binding.sidebar.background = if (expanded) ContextCompat.getDrawable(this, R.drawable.sidebar_bg) else null
        sidebarAnimator?.cancel()
        sidebarAnimator = ValueAnimator.ofInt(from, to).apply {
            duration = 160
            addUpdateListener {
                binding.sidebar.layoutParams.width = it.animatedValue as Int
                binding.sidebar.requestLayout()
            }
            start()
        }
        val alpha = if (expanded) 1f else 0f
        (navItems.map { it.binding.label } + binding.sidebarHeader.labels() + binding.sidebarFooter.labels()).forEach {
            it.animate().alpha(alpha).setDuration(160).start()
        }
    }

    private fun android.view.ViewGroup.labels(): List<View> =
        (0 until childCount).mapNotNull { getChildAt(it).findViewById(R.id.label) }

    private fun View.isInside(parent: View): Boolean {
        var v: View? = this
        while (v != null) {
            if (v == parent) return true
            v = v.parent as? View
        }
        return false
    }

    /** 返回键：内容区 → 侧边栏 → 首页 → 退出，和官方客户端一致 */
    private val keyThrottle = KeyRepeatThrottle()
    private var lastContentFocus: java.lang.ref.WeakReference<View>? = null
    private var restoringFocus = false

    /**
     * 上下键的边界规则：
     * - 侧边栏里只在侧边栏各项之间按顺序移动（含底部的设置），到头不动，只有右键离开侧边栏；
     * - 内容区（首页、媒体库、搜索）到了第一行/最后一行再按，不进侧边栏，无动作。
     */
    private fun handleVerticalKey(event: KeyEvent): Boolean {
        val up = event.keyCode == KeyEvent.KEYCODE_DPAD_UP
        if (!up && event.keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false
        val focused = currentFocus ?: return false
        if (focused.isInside(binding.sidebar)) {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            val items = sidebarFocusables()
            val i = items.indexOf(focused)
            val next = items.getOrNull(if (up) i - 1 else i + 1)
            next?.requestFocus()
            return true
        }
        if (focused.isInside(binding.content)) {
            if (event.action != KeyEvent.ACTION_DOWN) return false
            // 用 View.focusSearch 而不是 FocusFinder：它会经过列表，下一行还没滚进屏幕时列表会先布局出来再找；
            // FocusFinder 只看屏幕上已有的 View，找不到就会被误判为“到头了”
            val direction = if (up) View.FOCUS_UP else View.FOCUS_DOWN
            val next = focused.focusSearch(direction)
            if (next == null || next === focused || next.isInside(binding.sidebar)) return true
            next.requestFocus(direction)
            return true
        }
        return false
    }

    /** 侧边栏里可选的项，从上到下：切换服务器、搜索、主页、各媒体库，然后底部的播放列表、设置 */
    private fun sidebarFocusables(): List<View> =
        listOf(binding.sidebarHeader, binding.sidebarItems, binding.sidebarFooter).flatMap { group ->
            (0 until group.childCount).map { group.getChildAt(it) }.filter { it.visibility == View.VISIBLE && it.isFocusable }
        }

    private fun restoreContentFocus(): Boolean {
        val target = lastContentFocus?.get() ?: return false
        if (!target.isAttachedToWindow || !target.isShown || !target.isFocusable || !target.isInside(binding.content)) return false
        // 用鼠标点过之后处于触摸模式，普通 requestFocus 会失败
        val ok = if (target.isInTouchMode) target.requestFocusFromTouch() else target.requestFocus()
        return ok && target.hasFocus()
    }
    private var lastBackAt = 0L
    private var exitToast: Toast? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 长按限速只用于内容区（卡片要布局、加载图片）；侧边栏项目少，保持系统速度
        val inSidebar = currentFocus?.isInside(binding.sidebar) == true
        if (!inSidebar && keyThrottle.shouldDrop(event)) return true
        if (handleVerticalKey(event)) return true
        // 侧边栏按右：回到进入侧边栏之前的那张卡片，而不是按几何位置找最近的。
        // 恢复不了（卡片已被替换、触摸模式等）就交给系统默认导航，绝不吞掉按键
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && sidebarExpanded) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                restoringFocus = restoreContentFocus()
                if (restoringFocus) return true
            } else if (event.action == KeyEvent.ACTION_UP && restoringFocus) {
                restoringFocus = false
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            if (!sidebarExpanded) {
                navItems.firstOrNull { it.binding.root.isActivated }?.binding?.root?.requestFocus()
                return true
            }
            val home = navItems.firstOrNull { it.target == NavTarget.Home }
            if (home != null && !home.binding.root.isActivated) {
                navigate(home.target)
                return true
            }
            // 最后一步：2 秒内再按一次才退出，避免误触
            val now = SystemClock.uptimeMillis()
            if (now - lastBackAt < EXIT_WINDOW_MS) {
                exitToast?.cancel()
                finish()
            } else {
                lastBackAt = now
                exitToast = Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).also { it.show() }
            }
            return true
        }
        // 返回键全部在抬起时处理，按下事件不交给系统（否则系统会直接关闭页面）
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- HomeHost ----

    override fun showBackdrop(item: MediaItem?) {
        val ref = item?.backdrop
        if (ref == backdropRef) return
        backdropRef = ref
        backdropJob?.cancel()
        // 焦点快速移动时不要每一格都去加载大图
        backdropJob = lifecycleScope.launch {
            delay(if (ref == null) 0 else 300)
            if (ref == null) {
                binding.backdrop.setImageDrawable(null)
            } else {
                val w = binding.backdropFrame.width.takeIf { it > 0 } ?: 1280
                val h = binding.backdropFrame.height.takeIf { it > 0 } ?: 720
                Backdrop.showIn(binding.backdrop, server.imageUrl(ref, w, h), alpha = 0.85f)
            }
        }
    }

    override fun openItem(item: MediaItem) = Navigator.open(this, item)

    override fun showItemMenu(item: MediaItem) {
        ItemMenu.show(this, server, item) { changed ->
            (supportFragmentManager.findFragmentById(R.id.content) as? ItemChangeListener)?.onItemChanged(changed)
        }
    }

    /** 启动时在后台确认保存的地址还能用，不能用就自动换地址并重新加载页面 */
    private fun checkConnection() {
        lifecycleScope.launch {
            val app = LumyApp.instance
            if (server.isReachable()) {
                // 能用也再挑一次：在外面连过公网、回家后应切到局域网。不刷新页面，之后的请求和播放走新地址
                if (app.refreshBestConnection()) app.currentServer()?.let { server = it }
                return@launch
            }
            Toast.makeText(this@HomeActivity, R.string.reconnecting, Toast.LENGTH_SHORT).show()
            if (app.reconnect()) recreate() else reconnect()
        }
    }

    override fun reconnect() {
        lifecycleScope.launch {
            if (LumyApp.instance.reconnect()) {
                recreate()
                return@launch
            }
            // 自动重连失败（服务器离线或账号变化）：Plex 让用户在账号下重新选择；
            // Jellyfin / Emby 只有一个地址，只能提示
            val token = LumyApp.instance.currentEntry?.accountToken
            if (server.session.kind == ServerKind.PLEX && token != null) {
                startActivity(ServersActivity.intent(this@HomeActivity, token, preferredId = server.session.serverId, replaceId = entryId))
            } else {
                Toast.makeText(this@HomeActivity, getString(R.string.servers_unreachable, LumyApp.instance.currentEntry?.displayName ?: server.session.name), Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val EXIT_WINDOW_MS = 2_000L

        /** 换了服务器（或刚添加）后重新打开首页，清掉之前的页面 */
        fun restart(context: Context) {
            context.startActivity(
                Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

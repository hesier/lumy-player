package com.hesier.lumy

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Build
import com.hesier.lumy.core.HttpStatusException
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import com.hesier.lumy.jellyfin.JellyfinClient
import com.hesier.lumy.jellyfin.JellyfinClientInfo
import com.hesier.lumy.jellyfin.JellyfinConnector
import com.hesier.lumy.jellyfin.JellyfinMediaServer
import com.hesier.lumy.plex.PlexAccount
import com.hesier.lumy.plex.PlexClient
import com.hesier.lumy.plex.PlexClientInfo
import com.hesier.lumy.plex.PlexConnectionResolver
import com.hesier.lumy.plex.PlexMediaServer
import com.hesier.lumy.data.AppLanguage
import com.hesier.lumy.data.SavedServer
import com.hesier.lumy.data.SessionStore
import com.hesier.lumy.util.DebugLog
import com.hesier.lumy.util.withLegacyTls
import com.hesier.lumy.data.Settings
import com.hesier.lumy.data.TrackMemory
import coil.ImageLoader
import coil.ImageLoaderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cache
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

class LumyApp : Application(), ImageLoaderFactory {

    lateinit var store: SessionStore
        private set
    lateinit var settings: Settings
        private set
    lateinit var plex: PlexClient
        private set
    lateinit var trackMemory: TrackMemory
        private set
    val plexAccount by lazy { PlexAccount(plex) }
    val plexResolver by lazy { PlexConnectionResolver(plex) }
    lateinit var jellyfin: JellyfinClient
        private set
    val jellyfinConnector by lazy { JellyfinConnector(jellyfin) }

    private var server: MediaServer? = null

    /** 不随页面销毁的协程作用域，用于退出播放时的最后一次进度上报 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val pendingReports = mutableListOf<Job>()

    /** 在后台上报播放进度 / 停止播放，页面刷新前可以等它完成 */
    fun report(block: suspend () -> Unit) {
        val job = appScope.launch { runCatching { block() } }
        pendingReports += job
        job.invokeOnCompletion { appScope.launch { pendingReports.remove(job) } }
    }

    /**
     * 等正在进行的进度上报完成再刷新：退出播放器后立刻刷新首页 / 详情，
     * 请求可能比“停止播放”的上报先到服务器，拿到的还是旧的继续观看和进度
     */
    suspend fun awaitPendingReports(timeoutMs: Long = 3_000) {
        val jobs = pendingReports.toList()
        if (jobs.isEmpty()) return
        withTimeoutOrNull(timeoutMs) { jobs.joinAll() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        DebugLog.init(this)
        store = SessionStore(this)
        settings = Settings(this)
        trackMemory = TrackMemory(this)
        plex = PlexClient(
            PlexClientInfo(
                product = getString(R.string.app_name),
                version = BuildConfig.VERSION_NAME,
                clientId = store.clientId,
                platformVersion = Build.VERSION.RELEASE,
                device = Build.MODEL,
                language = uiLocale().toLanguageTag(),
            ),
            http = PlexClient.defaultHttpClient().withLegacyTls(this),
        )
        // 和 Plex 共用连接池
        jellyfin = JellyfinClient(
            JellyfinClientInfo(
                product = getString(R.string.app_name),
                version = BuildConfig.VERSION_NAME,
                deviceId = store.clientId,
                device = Build.MODEL,
            ),
            plex.http,
        )
        store.migrateLegacy()?.let(settings::migrateSidebar)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 跟随系统时，系统改了语言 Plex 的行标题也要跟着变
        plex.language = uiLocale().toLanguageTag()
    }

    /** 页面由 ThemedActivity 重建；这里同步 Plex 请求头（Plex 首页的行标题由服务端按它翻译） */
    fun setLanguage(language: AppLanguage) {
        settings.language = language
        plex.language = uiLocale().toLanguageTag()
    }

    /** 界面实际使用的语言 */
    fun uiLocale(): Locale = settings.language.locale ?: systemLocale()

    @Suppress("DEPRECATION")
    private fun systemLocale(): Locale {
        val config = Resources.getSystem().configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) config.locales[0] else config.locale
    }

    /** Application 自己的资源始终是系统语言，设置里选了语言时要换成对应语言的 Context 再取字符串 */
    fun localizedContext(): Context = settings.language.locale?.let {
        createConfigurationContext(Configuration(resources.configuration).apply { setLocale(it) })
    } ?: this

    /** 当前服务器的本地记录（不是服务端的 id）；没有添加任何服务器时为 null */
    val currentEntry: SavedServer? get() = store.current

    /** 当前连接的服务器；还没添加服务器时返回 null */
    fun currentServer(): MediaServer? {
        val session = store.current?.session ?: return null
        server?.let { if (it.session == session) return it }
        return createServer(session).also { server = it }
    }

    /** 添加一台服务器并切换过去；同一台服务器重复添加也是新的一项 */
    fun addServer(session: ServerSession, accountToken: String? = null, userName: String? = null, customName: String? = null) {
        val saved = SavedServer(UUID.randomUUID().toString(), session, accountToken, userName, customName?.trim()?.takeIf { it.isNotEmpty() })
        store.add(saved)
        switchTo(saved.id)
    }

    /** 把已有的一项换成新的连接（重新选择 Plex 服务器、地址变了），侧边栏设置跟着 id 保留 */
    fun replaceServer(id: String, session: ServerSession, accountToken: String?) {
        val entry = store.servers.firstOrNull { it.id == id } ?: return addServer(session, accountToken)
        store.update(entry.copy(session = session, accountToken = accountToken ?: entry.accountToken))
        switchTo(id)
    }

    /** 名称留空则恢复为服务端返回的名称 */
    fun renameServer(id: String, name: String) {
        val entry = store.servers.firstOrNull { it.id == id } ?: return
        store.update(entry.copy(customName = name.trim().takeIf { it.isNotEmpty() }))
    }

    fun switchTo(id: String) {
        store.currentId = id
        server = null
    }

    /** 删除后当前服务器变为列表里的第一台；全部删完时 [currentServer] 返回 null */
    fun removeServer(id: String) {
        store.remove(id)
        settings.forgetServer(id)
        trackMemory.forgetServer(id)
        server = null
    }

    private val reconnectLock = Mutex()

    /**
     * 服务器地址变了（换了网络、公网 IP 变化、端口映射改了）时重新探测。
     * Plex 可以从 plex.tv 拿到服务器的最新地址；Jellyfin / Emby 只有用户填的地址，只能确认它是否恢复了。
     * 成功返回 true，之后 [currentServer] 会返回新地址的实例。
     */
    suspend fun reconnect(): Boolean = reconnectLock.withLock {
        val entry = store.current ?: return false
        val current = currentServer() ?: return false
        // 并发调用时，前一个已经换好地址就不再重复探测
        if (current.isReachable()) return true
        if (entry.kind != ServerKind.PLEX) return false
        val token = entry.accountToken ?: return false
        val resource = runCatching { plexAccount.servers(token) }.getOrNull()
            ?.firstOrNull { it.clientIdentifier == entry.session.serverId } ?: return false
        val fresh = runCatching { plexResolver.resolve(resource) }.getOrNull() ?: return false
        store.update(entry.copy(session = fresh))
        server = null
        true
    }

    /**
     * 重新选一次最优地址（例如在外面用公网连上过，回家后应改走局域网）。
     * 保存的地址仍然可用时也会做；选出的地址不同就切换，后续请求和播放都走新地址。
     * 只有 Plex 有多个候选地址，Jellyfin / Emby 直接返回 false。
     * @return 地址是否变了
     */
    suspend fun refreshBestConnection(): Boolean = reconnectLock.withLock {
        val entry = store.current ?: return false
        if (entry.kind != ServerKind.PLEX) return false
        val session = entry.session
        val token = entry.accountToken ?: return false
        val resource = runCatching { plexAccount.servers(token) }.getOrNull()
            ?.firstOrNull { it.clientIdentifier == session.serverId } ?: return false
        val best = runCatching { plexResolver.resolve(resource) }.getOrNull() ?: return false
        if (best.baseUrl == session.baseUrl) return false
        DebugLog.i("LumyConn", "switch ${session.baseUrl} -> ${best.baseUrl}")
        store.update(entry.copy(session = best))
        server = null
        true
    }

    /** 网络错误时自动重连一次再重试；4xx/5xx 等服务端错误不重试 */
    suspend fun <T> withReconnect(block: suspend (MediaServer) -> T): T {
        val server = currentServer() ?: throw IOException(localizedContext().getString(R.string.no_server))
        return try {
            block(server)
        } catch (e: IOException) {
            if (e is HttpStatusException || !reconnect()) throw e
            block(currentServer()!!)
        }
    }

    private fun createServer(session: ServerSession): MediaServer = when (session.kind) {
        ServerKind.PLEX -> PlexMediaServer(session, plex)
        ServerKind.JELLYFIN, ServerKind.EMBY -> JellyfinMediaServer(session, jellyfin)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient {
            plex.http.newBuilder()
                .cache(Cache(File(cacheDir, "images"), 150L * 1024 * 1024))
                .build()
        }
        // 海报不需要透明通道，RGB_565 能省一半内存，对低端盒子很重要
        .bitmapConfig(Bitmap.Config.RGB_565)
        .allowRgb565(true)
        .crossfade(180)
        .respectCacheHeaders(false)
        .build()

    companion object {
        lateinit var instance: LumyApp
            private set
    }
}

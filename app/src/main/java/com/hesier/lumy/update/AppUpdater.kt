package com.hesier.lumy.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as SystemSettings
import androidx.core.content.FileProvider
import com.hesier.lumy.BuildConfig
import com.hesier.lumy.LumyApp
import com.hesier.lumy.net.UpdateClient
import com.hesier.lumy.net.UpdateInfo
import com.hesier.lumy.ui.widget.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

enum class InstallResult {
    /** 系统安装界面已打开 */
    STARTED,
    /** 还没允许安装未知应用，已打开对应的设置页 */
    NEEDS_PERMISSION,
    /** 没有安装界面或设置页（部分电视系统砍掉了），只能让用户自己下载安装 */
    UNAVAILABLE,
}

/** 从 GitHub Release 检查、下载和安装新版本；Android 不允许静默安装，最后一步由用户在系统界面确认 */
object AppUpdater {
    private const val REPO = "hesier/lumy-player"
    const val RELEASES_URL = "https://github.com/$REPO/releases/latest"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private val app get() = LumyApp.instance
    private val client by lazy { UpdateClient(app.plex.http, REPO) }

    /** 最近一次检查到的新版本，设置页显示用 */
    var available: UpdateInfo? = null
        private set

    /** 有新版本时返回它；已是最新时顺便清掉之前下载的安装包 */
    suspend fun check(): UpdateInfo? {
        val info = client.latest(app.settings.updateMirror)
        app.settings.lastUpdateCheck = System.currentTimeMillis()
        available = info.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        if (available == null) withContext(Dispatchers.IO) { updateDir().deleteRecursively() }
        return available
    }

    /**
     * 启动时的自动检查：距上次检查不到一天就跳过，失败不提示，忽略过的版本不再提示。
     * debug 包包名不同，装上的会是另一个 app，所以不自动检查
     */
    suspend fun autoCheck(): UpdateInfo? {
        val settings = app.settings
        if (BuildConfig.DEBUG || !settings.autoCheckUpdates) return null
        // 用 abs：系统时间被往回调过时也能重新检查
        if (abs(System.currentTimeMillis() - settings.lastUpdateCheck) < CHECK_INTERVAL_MS) return null
        val info = attempt { check() }.getOrNull() ?: return null
        return info.takeIf { it.versionCode != settings.skippedUpdate }
    }

    suspend fun download(info: UpdateInfo, onProgress: (Long, Long) -> Unit): File =
        client.download(info, app.settings.updateMirror, File(updateDir(), "lumy-${info.versionName}.apk"), onProgress)

    fun install(activity: Activity, apk: File): InstallResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(SystemSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            return if (activity.tryStart(settings)) InstallResult.NEEDS_PERMISSION else InstallResult.UNAVAILABLE
        }
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setData(FileProvider.getUriForFile(activity, "${activity.packageName}.update", apk))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            // 7.0 以前只能给 file://，安装器是另一个进程，要让它能进目录、读文件
            apk.parentFile?.setExecutable(true, false)
            apk.setReadable(true, false)
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive")
        }
        return if (activity.tryStart(intent)) InstallResult.STARTED else InstallResult.UNAVAILABLE
    }

    /** 当前界面语言的更新说明，去掉 Markdown 标记 */
    fun notes(info: UpdateInfo): String {
        val key = if (app.uiLocale().language == "zh") "zh" else "en"
        val text = info.notes[key] ?: info.notes["en"] ?: return ""
        val lines = text.lines()
        // 标题后面的空行去掉，标题和它下面的条目挨在一起
        return lines.filterIndexed { i, line -> !(line.isBlank() && i > 0 && lines[i - 1].startsWith("#")) }
            .joinToString("\n") { line ->
                line.replace(Regex("^#+\\s*"), "")
                    .replace(Regex("^- "), "• ")
                    .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
                    .replace("**", "")
                    .replace("`", "")
            }.trim()
    }

    private fun updateDir() = File(app.cacheDir, "update")

    private fun Activity.tryStart(intent: Intent) = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

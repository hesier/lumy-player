package com.hesier.lumy.data

import android.content.Context
import com.hesier.lumy.core.SubtitleRule
import com.hesier.lumy.core.TrackLanguage
import java.util.Locale

enum class SkipMode { OFF, BUTTON, AUTO }

/** 画面比例：适应屏幕（留黑边）、裁剪填满、拉伸全屏 */
enum class VideoScale { FIT, ZOOM, FILL }

/** 默认字幕：跟随服务器、按首选语言、仅强制字幕、关闭 */
enum class SubtitleMode { SERVER, LANGUAGE, FORCED_ONLY, OFF }

/** 界面主题；播放器始终是深色 */
enum class ThemeMode { DARK, LIGHT, SYSTEM }

/** 界面语言；[locale] 为 null 表示跟随系统。zh-TW 在各版本上都能匹配到 values-zh-rTW */
enum class AppLanguage(val locale: Locale?) {
    SYSTEM(null),
    ENGLISH(Locale.ENGLISH),
    SIMPLIFIED_CHINESE(Locale.SIMPLIFIED_CHINESE),
    TRADITIONAL_CHINESE(Locale.TRADITIONAL_CHINESE),
}

/** 用户偏好设置，播放器每次起播时读取 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 把 AC3/DTS 等原始码流交给功放；正在直通的音轨无法倍速 */
    var passthrough: Boolean
        get() = prefs.getBoolean("passthrough", false)
        set(v) = prefs.edit().putBoolean("passthrough", v).apply()

    /** 对应播放器画质菜单的下标，0 为原画 */
    var defaultQuality: Int
        get() = prefs.getInt("default_quality", 0)
        set(v) = prefs.edit().putInt("default_quality", v).apply()

    var videoScale: VideoScale
        get() = prefs.getString("video_scale", null)
            ?.let { runCatching { VideoScale.valueOf(it) }.getOrNull() } ?: VideoScale.FIT
        set(v) = prefs.edit().putString("video_scale", v.name).apply()

    /** [SUBTITLE_POSITIONS] 的下标 */
    var subtitlePosition: Int
        get() = prefs.getInt("subtitle_position", 1).coerceIn(0, SUBTITLE_POSITIONS.lastIndex)
        set(v) = prefs.edit().putInt("subtitle_position", v).apply()

    /** 字幕下面加半透明黑底 */
    var subtitleBackground: Boolean
        get() = prefs.getBoolean("subtitle_background", false)
        set(v) = prefs.edit().putBoolean("subtitle_background", v).apply()

    var subtitleScale: Float
        get() = prefs.getFloat("subtitle_scale", 1f)
        set(v) = prefs.edit().putFloat("subtitle_scale", v).apply()

    /** 关闭后只直接播放，设备不支持的格式会报错而不是交给服务器转码 */
    var allowTranscode: Boolean
        get() = prefs.getBoolean("allow_transcode", true)
        set(v) = prefs.edit().putBoolean("allow_transcode", v).apply()

    /**
     * 电视是否支持 HDR。系统检测到的结果不可靠（不少盒子总是报支持或什么都不报），所以让用户自己选。
     * 关闭后 HDR 片源交给服务器转成 SDR，避免画面发灰
     */
    var displaySupportsHdr: Boolean
        get() = prefs.getBoolean("display_supports_hdr", true)
        set(v) = prefs.edit().putBoolean("display_supports_hdr", v).apply()

    /** 默认不让服务器烧录字幕：烧录要重新编码视频，画质和服务器负担都不好 */
    var allowSubtitleBurn: Boolean
        get() = prefs.getBoolean("allow_subtitle_burn", false)
        set(v) = prefs.edit().putBoolean("allow_subtitle_burn", v).apply()

    /**
     * 不在侧边栏显示的媒体库 id（以及 [PLAYLISTS_ID]）。按服务器分开保存：
     * 不同服务器的媒体库 id 会重复（Plex 都是 1、2、3…）
     */
    fun hiddenSidebarItems(serverId: String): Set<String> =
        prefs.getStringSet("hidden_sidebar:$serverId", emptySet())!!.toSet()

    fun setHiddenSidebarItems(serverId: String, ids: Set<String>) =
        prefs.edit().putStringSet("hidden_sidebar:$serverId", ids).apply()

    /** 侧边栏媒体库的顺序（媒体库 id）；不在列表里的新媒体库排在后面 */
    fun sidebarOrder(serverId: String): List<String> =
        prefs.getString("sidebar_order:$serverId", null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

    fun setSidebarOrder(serverId: String, ids: List<String>) =
        prefs.edit().putString("sidebar_order:$serverId", ids.joinToString(",")).apply()

    /** 删除服务器时一起清掉 */
    fun forgetServer(serverId: String) =
        prefs.edit().remove("hidden_sidebar:$serverId").remove("sidebar_order:$serverId").apply()

    /** 旧版本只有一台服务器，侧边栏设置没有按服务器区分，归到升级后的那台 */
    fun migrateSidebar(serverId: String) {
        val hidden = prefs.getStringSet("hidden_sidebar", null)
        val order = prefs.getString("sidebar_order", null)
        prefs.edit().apply {
            hidden?.let { putStringSet("hidden_sidebar:$serverId", it) }
            order?.let { putString("sidebar_order:$serverId", it) }
            remove("hidden_sidebar")
            remove("sidebar_order")
        }.apply()
    }

    var theme: ThemeMode
        get() = prefs.getString("theme", null)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK
        set(v) = prefs.edit().putString("theme", v.name).apply()

    /** 改语言要走 [com.hesier.lumy.LumyApp.setLanguage]，Plex 请求头也要跟着换 */
    var language: AppLanguage
        get() = prefs.getString("language", null)
            ?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() } ?: AppLanguage.SYSTEM
        set(v) = prefs.edit().putString("language", v.name).apply()

    /** 界面整体缩放（[UI_SCALES] 里的值），播放器不缩放 */
    var uiScale: Float
        get() = prefs.getFloat("ui_scale", 1f)
        set(v) = prefs.edit().putFloat("ui_scale", v).apply()

    /** 详情页底部各区块是否显示 */
    var detailShowCast: Boolean
        get() = prefs.getBoolean("detail_show_cast", true)
        set(v) = prefs.edit().putBoolean("detail_show_cast", v).apply()

    var detailShowSimilar: Boolean
        get() = prefs.getBoolean("detail_show_similar", true)
        set(v) = prefs.edit().putBoolean("detail_show_similar", v).apply()

    var detailShowFileInfo: Boolean
        get() = prefs.getBoolean("detail_show_file_info", true)
        set(v) = prefs.edit().putBoolean("detail_show_file_info", v).apply()

    var matchFrameRate: Boolean
        get() = prefs.getBoolean("match_frame_rate", false)
        set(v) = prefs.edit().putBoolean("match_frame_rate", v).apply()

    /**
     * 本机杜比视界解码器实际解不出画面的 profile（自动检测后记下），这些片源以后直接用 HEVC 解码器播放 HDR10 基础层。
     * 记的是设备能力，和服务器无关。
     */
    var brokenDolbyVisionProfiles: Set<Int>
        get() = prefs.getStringSet("broken_dovi_profiles", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = prefs.edit().putStringSet("broken_dovi_profiles", v.map { it.toString() }.toSet()).apply()

    var audioDelayMs: Long
        get() = prefs.getLong("audio_delay_ms", 0)
        set(v) = prefs.edit().putLong("audio_delay_ms", v).apply()

    var autoPlayNext: Boolean
        get() = prefs.getBoolean("auto_play_next", true)
        set(v) = prefs.edit().putBoolean("auto_play_next", v).apply()

    var skipIntro: SkipMode
        get() = enumOr(prefs.getString("skip_intro", null), SkipMode.BUTTON)
        set(v) = prefs.edit().putString("skip_intro", v.name).apply()

    var skipCredits: SkipMode
        get() = enumOr(prefs.getString("skip_credits", null), SkipMode.BUTTON)
        set(v) = prefs.edit().putString("skip_credits", v.name).apply()

    /** 首选音轨语言（[TrackLanguage] 的取值），null 表示跟随服务器 */
    var audioLanguage: String?
        get() = prefs.getString("audio_language", null)
        set(v) = prefs.edit().putString("audio_language", v).apply()

    var subtitleMode: SubtitleMode
        get() = prefs.getString("subtitle_mode", null)
            ?.let { runCatching { SubtitleMode.valueOf(it) }.getOrNull() } ?: SubtitleMode.SERVER
        set(v) = prefs.edit().putString("subtitle_mode", v.name).apply()

    var subtitleLanguage: String
        get() = prefs.getString("subtitle_language", null) ?: TrackLanguage.ZH_HANS
        set(v) = prefs.edit().putString("subtitle_language", v).apply()

    /** 设置里的字幕规则；跟随服务器时为 null */
    val subtitleRule: SubtitleRule?
        get() = when (subtitleMode) {
            SubtitleMode.SERVER -> null
            SubtitleMode.LANGUAGE -> SubtitleRule.Language(subtitleLanguage)
            SubtitleMode.FORCED_ONLY -> SubtitleRule.ForcedOnly(subtitleLanguage)
            SubtitleMode.OFF -> SubtitleRule.Off
        }

    private fun enumOr(name: String?, default: SkipMode) =
        name?.let { runCatching { SkipMode.valueOf(it) }.getOrNull() } ?: default

    companion object {
        /** 按保存的顺序排列，没排过序的保持原顺序放在后面 */
        fun <T> applyOrder(items: List<T>, order: List<String>, id: (T) -> String): List<T> {
            val rank = order.withIndex().associate { it.value to it.index }
            return items.withIndex()
                .sortedWith(compareBy({ rank[id(it.value)] ?: Int.MAX_VALUE }, { it.index }))
                .map { it.value }
        }

        const val PLAYLISTS_ID = "__playlists"
        val SUBTITLE_SCALES = listOf(0.8f, 1f, 1.25f, 1.5f)
        val UI_SCALES = listOf(0.8f, 0.9f, 1f, 1.1f, 1.2f)
        /** 字幕离底部的距离（占画面高度的比例）：靠下、标准、偏高、更高 */
        val SUBTITLE_POSITIONS = listOf(0.02f, 0.06f, 0.12f, 0.2f)
    }
}

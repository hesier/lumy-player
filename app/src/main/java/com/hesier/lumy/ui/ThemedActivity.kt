package com.hesier.lumy.ui

import android.content.Context
import android.content.res.Configuration
import androidx.fragment.app.FragmentActivity
import com.hesier.lumy.LumyApp
import com.hesier.lumy.data.AppLanguage
import com.hesier.lumy.data.ThemeMode
import kotlin.math.roundToInt

/**
 * 所有页面的基类：按设置里的主题改写 uiMode 的夜间位，颜色从 values（浅色）/ values-night（深色）里取；
 * 设置里选了界面语言时同时覆盖 locale，设了界面缩放时按比例改写 densityDpi。没用 AppCompat，所以自己覆盖配置；API 21 也能用。
 */
abstract class ThemedActivity : FragmentActivity() {
    /** 播放器固定深色：看视频时浅色控件太刺眼，字幕和遮罩也是按深色设计的 */
    protected open val alwaysDark: Boolean get() = false

    private var appliedNight = true
    private var appliedLanguage = AppLanguage.SYSTEM
    private var appliedScale = 1f

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        val system = newBase.resources.configuration
        appliedNight = wantsNight(system)
        val night = if (appliedNight) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        appliedLanguage = LumyApp.instance.settings.language
        appliedScale = wantedScale()
        applyOverrideConfiguration(Configuration().apply {
            uiMode = (system.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            appliedLanguage.locale?.let(::setLocale)
            if (appliedScale != 1f) {
                // dp 尺寸跟着变大变小；屏幕的 dp 宽高也要同步，否则和 density 对不上
                densityDpi = (system.densityDpi * appliedScale).roundToInt()
                screenWidthDp = (system.screenWidthDp / appliedScale).roundToInt()
                screenHeightDp = (system.screenHeightDp / appliedScale).roundToInt()
                smallestScreenWidthDp = (system.smallestScreenWidthDp / appliedScale).roundToInt()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // 在设置里换了主题或语言后，返回栈里的页面回到前台时重建
        recreateIfThemeChanged()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // configChanges 包含 uiMode，跟随系统时系统切换深浅色不会自动重建
        recreateIfThemeChanged()
    }

    private fun recreateIfThemeChanged() {
        if (wantsNight(baseContext.resources.configuration) != appliedNight ||
            LumyApp.instance.settings.language != appliedLanguage ||
            wantedScale() != appliedScale
        ) {
            recreate()
        }
    }

    /** 播放器也跟着缩放：控制栏和选集按 dp 布局，字幕按画面比例算大小，不受影响 */
    private fun wantedScale() = LumyApp.instance.settings.uiScale

    private fun wantsNight(system: Configuration): Boolean {
        if (alwaysDark) return true
        return when (LumyApp.instance.settings.theme) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            // 系统没说明（不少电视盒子如此）时按深色
            ThemeMode.SYSTEM -> system.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_NO
        }
    }
}

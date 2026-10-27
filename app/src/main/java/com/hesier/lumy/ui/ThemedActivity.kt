package com.hesier.lumy.ui

import android.content.Context
import android.content.res.Configuration
import androidx.fragment.app.FragmentActivity
import com.hesier.lumy.LumyApp
import com.hesier.lumy.data.AppLanguage
import com.hesier.lumy.data.ThemeMode

/**
 * 所有页面的基类：按设置里的主题改写 uiMode 的夜间位，颜色从 values（浅色）/ values-night（深色）里取；
 * 设置里选了界面语言时同时覆盖 locale。没用 AppCompat，所以自己覆盖配置；API 21 也能用。
 */
abstract class ThemedActivity : FragmentActivity() {
    /** 播放器固定深色：看视频时浅色控件太刺眼，字幕和遮罩也是按深色设计的 */
    protected open val alwaysDark: Boolean get() = false

    private var appliedNight = true
    private var appliedLanguage = AppLanguage.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        val system = newBase.resources.configuration
        appliedNight = wantsNight(system)
        val night = if (appliedNight) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        appliedLanguage = LumyApp.instance.settings.language
        applyOverrideConfiguration(Configuration().apply {
            uiMode = (system.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            appliedLanguage.locale?.let(::setLocale)
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
            LumyApp.instance.settings.language != appliedLanguage
        ) {
            recreate()
        }
    }

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

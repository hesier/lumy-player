package com.hesier.lumy.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.hesier.lumy.R
import com.hesier.lumy.data.DanmakuOnlineSource
import com.hesier.lumy.data.DanmakuServices
import com.hesier.lumy.data.DanmakuSourceSettings
import com.hesier.lumy.data.DanmakuStore
import com.hesier.lumy.net.danmaku.DanmakuApi
import com.hesier.lumy.net.danmaku.DanmakuException
import com.hesier.lumy.net.danmaku.DanmakuFailure
import com.hesier.lumy.ui.widget.InputPanel
import com.hesier.lumy.ui.widget.Option
import com.hesier.lumy.ui.widget.OptionsPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class DanmakuSourcesSection(
    private val activity: SettingsActivity,
    private val settings: DanmakuSourceSettings,
    private val changed: () -> Unit,
) {
    private var testing = false

    fun rows(): List<SettingsActivity.Row> = listOf(
        SettingsActivity.Row(text(R.string.danmaku_source_title), sourceLabel(), sourceDetail(),
            detailMaxLines = Int.MAX_VALUE) { chooseSource() },
        SettingsActivity.Row(text(R.string.danmaku_custom_url), addressSummary(),
            text(R.string.danmaku_custom_url_hint)) { editAddress() },
        SettingsActivity.Row(text(R.string.danmaku_test_api), "", text(R.string.danmaku_test_detail)) { testApi() },
        SettingsActivity.Row(text(R.string.danmaku_clear_cache), "", text(R.string.danmaku_clear_cache_detail)) {
            activity.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { DanmakuStore(activity).clearCache() }
                    toast(R.string.danmaku_cleared)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { toast(R.string.danmaku_status_error) }
            }
        },
        SettingsActivity.Row(text(R.string.danmaku_clear_matches), "", text(R.string.danmaku_clear_matches_detail)) {
            confirmClearMatches()
        },
    )

    private fun sourceLabel(source: DanmakuOnlineSource = settings.onlineSource) = text(when {
        source == DanmakuOnlineSource.CUSTOM -> R.string.danmaku_source_custom
        officialUnavailable() -> R.string.danmaku_source_official_pending
        else -> R.string.danmaku_source_official_limited
    })

    private fun officialUnavailable() = runCatching { DanmakuServices(activity).official() }.isFailure

    private fun sourceDetail(): String {
        val officialHint = when {
            settings.onlineSource == DanmakuOnlineSource.CUSTOM -> null
            officialUnavailable() -> text(R.string.danmaku_official_unavailable)
            else -> text(R.string.danmaku_official_quota_hint)
        }
        return listOfNotNull(officialHint, text(R.string.danmaku_self_host_hint)).joinToString("\n")
    }

    private fun addressSummary(): String {
        val url = settings.customUrl.takeIf { it.isNotEmpty() }?.toHttpUrl()
            ?: return text(R.string.danmaku_source_not_configured)
        // 只显示主机和端口，路径可能含访问令牌。
        val host = if (':' in url.host) "[${url.host}]" else url.host
        return activity.getString(R.string.danmaku_source_configured, "$host:${url.port}")
    }

    private fun chooseSource() {
        val sources = DanmakuOnlineSource.entries
        OptionsPanel(activity, text(R.string.danmaku_source_title), sources.map { source ->
            Option(sourceLabel(source), selected = source == settings.onlineSource)
        }) { index ->
            if (sources[index] == DanmakuOnlineSource.CUSTOM && settings.customUrl.isEmpty()) {
                toast(R.string.danmaku_source_configure_first)
                editAddress()
            } else {
                settings.onlineSource = sources[index]
                changed()
            }
        }.show()
    }

    private fun editAddress() {
        InputPanel(activity, text(R.string.danmaku_custom_url), settings.customUrl,
            text(R.string.danmaku_custom_url_hint), text(R.string.server_rename_save)) { value ->
            if (runCatching { settings.customUrl = value }.isFailure) {
                toast(R.string.danmaku_custom_url_invalid)
            } else {
                if (settings.customUrl.isEmpty() && settings.onlineSource == DanmakuOnlineSource.CUSTOM)
                    settings.onlineSource = DanmakuOnlineSource.OFFICIAL
                changed()
            }
        }.show()
    }

    private fun testApi() {
        if (testing) return
        testing = true
        toast(R.string.danmaku_test_running)
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val endpoint = DanmakuServices(activity).endpoint
                    DanmakuApi(testClient, endpoint).search("LumyConnectivityProbe")
                }
                toast(R.string.danmaku_test_success)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: DanmakuException) { toast(danmakuErrorText(activity, error.reason)) }
            catch (_: Exception) { toast(R.string.danmaku_error_connection) }
            finally { testing = false }
        }
    }

    private fun confirmClearMatches() {
        OptionsPanel(activity, text(R.string.danmaku_clear_matches_confirm), listOf(
            Option(text(R.string.cancel), selected = true), Option(text(R.string.danmaku_clear_matches)),
        )) {
            if (it == 1) activity.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { DanmakuStore(activity).clearMatches() }
                    toast(R.string.danmaku_cleared)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { toast(R.string.danmaku_status_error) }
            }
        }.show()
    }

    private fun text(id: Int) = activity.getString(id)
    private fun toast(id: Int) = toast(text(id))
    private fun toast(value: String) = Toast.makeText(activity, value, Toast.LENGTH_LONG).show()

    private companion object {
        // 独立客户端不继承媒体服务器的登录头。
        val testClient = OkHttpClient.Builder().build()
    }
}

internal fun danmakuErrorText(context: Context, reason: DanmakuFailure): String = context.getString(when (reason) {
    DanmakuFailure.AUTH -> R.string.danmaku_error_auth
    DanmakuFailure.CONNECTION -> R.string.danmaku_error_connection
    DanmakuFailure.INVALID_RESPONSE -> R.string.danmaku_error_invalid_response
    DanmakuFailure.NOT_FOUND -> R.string.danmaku_error_not_found
    DanmakuFailure.UNAVAILABLE -> R.string.danmaku_error_unavailable
    DanmakuFailure.TOO_LARGE -> R.string.danmaku_error_too_large
    DanmakuFailure.RATE_LIMITED -> R.string.danmaku_error_rate_limited
})

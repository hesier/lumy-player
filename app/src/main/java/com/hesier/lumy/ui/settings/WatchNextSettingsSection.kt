package com.hesier.lumy.ui.settings

import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.hesier.lumy.R
import com.hesier.lumy.data.Settings
import com.hesier.lumy.tv.WatchNextManager
import com.hesier.lumy.ui.widget.attempt
import com.hesier.lumy.ui.widget.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class WatchNextSettingsSection(
    private val activity: SettingsActivity,
    private val settings: Settings,
    private val changed: () -> Unit,
) {
    private var busy = false
    private var checkingSupport = false

    fun rows(): List<SettingsActivity.Row> {
        if (!WatchNextManager.isSupported()) {
            if (!checkingSupport) {
                checkingSupport = true
                activity.lifecycleScope.launch(Dispatchers.Main) {
                    if (WatchNextManager.isAvailable(activity.applicationContext)) changed()
                }
            }
            return emptyList()
        }
        return listOf(
            SettingsActivity.Row(text(R.string.settings_watch_next),
                text(if (settings.watchNextEnabled) R.string.on else R.string.off),
                text(if (settings.watchNextEnabled && !WatchNextManager.isEnabled())
                    R.string.settings_watch_next_plex_sign_in_required else R.string.settings_watch_next_detail)) {
                perform { WatchNextManager.setEnabled(activity, !settings.watchNextEnabled) }
            },
            SettingsActivity.Row(text(R.string.settings_watch_next_refresh), status(),
                text(R.string.settings_watch_next_refresh_detail)) { refresh() },
            SettingsActivity.Row(text(R.string.settings_watch_next_clear), status(),
                text(R.string.settings_watch_next_clear_detail)) {
                perform(R.string.settings_watch_next_cleared) { WatchNextManager.clearAll(activity) }
            },
        )
    }

    private fun refresh() {
        if (busy) return
        if (!WatchNextManager.isEnabled()) {
            toast(text(if (settings.watchNextEnabled) R.string.settings_watch_next_plex_sign_in_required
                else R.string.settings_watch_next_disabled))
            return
        }
        perform(R.string.settings_watch_next_refreshed) { WatchNextManager.refresh(activity) }
    }

    private fun perform(successMessage: Int? = null, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        changed()
        activity.lifecycleScope.launch {
            try {
                attempt { action() }
                    .onSuccess {
                        if (WatchNextManager.isSupported()) successMessage?.let { toast(text(it)) }
                    }
                    .onFailure {
                        if (WatchNextManager.isSupported()) {
                            toast(activity.getString(R.string.settings_watch_next_failed, it.readableMessage()))
                        }
                    }
            } finally {
                busy = false
                changed()
            }
        }
    }

    private fun status() = if (busy) text(R.string.settings_watch_next_working) else ""
    private fun text(id: Int) = activity.getString(id)
    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
}

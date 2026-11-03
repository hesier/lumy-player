package com.hesier.lumy.ui.player

import android.view.View
import android.widget.TextView
import com.hesier.lumy.R
import com.hesier.lumy.core.PlaybackMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keep player feedback in one place without the system toast's two-line limit. */
class PlaybackNotice(private val view: TextView, private val scope: CoroutineScope) {
    private var mediaKey: String? = null
    private var last: Pair<String, Plan>? = null
    private var hideJob: Job? = null
    private var transcodeVisible = false
    private var message: String? = null
    private var pendingMessage: String? = null

    init {
        val context = view.context
        val icon = context.applicationInfo.loadIcon(context.packageManager)
        val density = context.resources.displayMetrics.density
        val size = (ICON_SIZE_DP * density).toInt()
        icon.setBounds(0, 0, size, size)
        view.setCompoundDrawablesRelative(icon, null, null, null)
        view.compoundDrawablePadding = (ICON_SPACING_DP * density).toInt()
    }

    fun show(mediaKey: String, plan: Plan, copiesVideo: Boolean = (plan.mode as? PlaybackMode.Transcode)?.copyVideo == true) {
        if (this.mediaKey != null && this.mediaKey != mediaKey) clear()
        this.mediaKey = mediaKey
        if (plan.mode !is PlaybackMode.Transcode) {
            last = null
            if (transcodeVisible) {
                hideJob?.cancel()
                showPendingMessage()
            }
            return
        }
        val key = mediaKey to plan
        if (last == key) return
        last = key
        if (!transcodeVisible) pendingMessage = message
        message = null
        val context = view.context
        val label = if (copiesVideo) R.string.player_stream_notice else R.string.player_transcode_notice
        display(context.getString(label, plan.reasons.text(context)), transcode = true)
    }

    fun showMessage(text: String) {
        if (transcodeVisible) {
            // New feedback supersedes obsolete loading/progress messages.
            pendingMessage = text
            return
        }
        message = text
        display(text, transcode = false)
    }

    fun clear() {
        hideJob?.cancel()
        hideJob = null
        mediaKey = null
        last = null
        transcodeVisible = false
        message = null
        pendingMessage = null
        view.visibility = View.GONE
    }

    private fun display(text: String, transcode: Boolean) {
        hideJob?.cancel()
        transcodeVisible = transcode
        view.text = text
        view.visibility = View.VISIBLE
        hideJob = scope.launch {
            delay(if (transcode) TRANSCODE_DURATION_MS else MESSAGE_DURATION_MS)
            showPendingMessage()
        }
    }

    private fun showPendingMessage() {
        val pending = pendingMessage
        pendingMessage = null
        transcodeVisible = false
        message = null
        if (pending != null) {
            message = pending
            display(pending, transcode = false)
        } else {
            view.visibility = View.GONE
        }
    }

    private companion object {
        const val TRANSCODE_DURATION_MS = 6_000L
        const val MESSAGE_DURATION_MS = 3_000L
        const val ICON_SIZE_DP = 24
        const val ICON_SPACING_DP = 12
    }
}

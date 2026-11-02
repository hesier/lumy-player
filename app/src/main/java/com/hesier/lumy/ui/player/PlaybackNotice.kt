package com.hesier.lumy.ui.player

import android.view.View
import android.widget.TextView
import com.hesier.lumy.R
import com.hesier.lumy.core.PlaybackMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keep all reasons visible without the system toast's two-line limit. */
class PlaybackNotice(private val view: TextView, private val scope: CoroutineScope) {
    private var last: Pair<String, Plan>? = null
    private var hideJob: Job? = null

    fun show(mediaKey: String, plan: Plan, copiesVideo: Boolean = (plan.mode as? PlaybackMode.Transcode)?.copyVideo == true) {
        if (plan.mode !is PlaybackMode.Transcode) {
            last = null
            hideJob?.cancel()
            view.visibility = View.GONE
            return
        }
        val key = mediaKey to plan
        if (last == key) return
        last = key
        val context = view.context
        val label = if (copiesVideo) R.string.player_stream_notice else R.string.player_transcode_notice
        view.text = context.getString(label, plan.reasons.text(context))
        view.visibility = View.VISIBLE
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(NOTICE_DURATION_MS)
            view.visibility = View.GONE
        }
    }

    private companion object {
        const val NOTICE_DURATION_MS = 6_000L
    }
}

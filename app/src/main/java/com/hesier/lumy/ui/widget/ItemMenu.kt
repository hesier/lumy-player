package com.hesier.lumy.ui.widget

import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.R
import com.hesier.lumy.ui.cardTitle
import com.hesier.lumy.ui.detail.DetailActivity
import kotlinx.coroutines.launch

/** 长按卡片弹出的操作菜单，和官方客户端一样 */
object ItemMenu {

    fun show(activity: FragmentActivity, server: MediaServer, item: MediaItem, onChanged: (MediaItem) -> Unit) {
        val actions = buildList<Pair<String, () -> Unit>> {
            add(activity.getString(R.string.open_detail) to { activity.startActivity(DetailActivity.intent(activity, item.id)) })
            val markLabel = if (item.watched) R.string.mark_unwatched else R.string.mark_watched
            add(activity.getString(markLabel) to { toggleWatched(activity, server, item, onChanged) })
            if (item.type == MediaType.EPISODE && item.grandparentId != null) {
                add(activity.getString(R.string.go_to_show) to {
                    activity.startActivity(DetailActivity.intent(activity, item.grandparentId!!, seasonId = item.parentId))
                })
            }
        }
        OptionsPanel(activity, item.cardTitle(), actions.map { Option(it.first) }) { actions[it].second() }.show()
    }

    fun toggleWatched(activity: FragmentActivity, server: MediaServer, item: MediaItem, onChanged: (MediaItem) -> Unit) {
        val watched = !item.watched
        activity.lifecycleScope.launch {
            attempt { server.setWatched(item.id, watched) }
                .onSuccess { onChanged(item.withWatched(watched)) }
                .onFailure { Toast.makeText(activity, activity.getString(R.string.load_failed, it.readableMessage()), Toast.LENGTH_SHORT).show() }
        }
    }
}

/** 本地先更新显示，不必等重新加载 */
fun MediaItem.withWatched(watched: Boolean): MediaItem = copy(
    watched = watched,
    viewOffsetMs = null,
    unwatchedCount = unwatchedCount?.let { if (watched) 0 else it },
)

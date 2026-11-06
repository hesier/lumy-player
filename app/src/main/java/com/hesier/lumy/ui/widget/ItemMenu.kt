package com.hesier.lumy.ui.widget

import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Job
import java.util.WeakHashMap
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.R
import com.hesier.lumy.ui.cardTitle
import com.hesier.lumy.ui.detail.DetailActivity
import com.hesier.lumy.tv.WatchNextManager
import kotlinx.coroutines.launch

/** 长按卡片弹出的操作菜单，和官方客户端一样 */
object ItemMenu {
    private val menuJobs = WeakHashMap<FragmentActivity, Job>()

    fun show(activity: FragmentActivity, server: MediaServer, item: MediaItem, onChanged: (MediaItem) -> Unit) {
        if (!server.supportsFavorites || !item.canFavorite) {
            showActions(activity, server, item, onChanged)
            return
        }
        menuJobs.remove(activity)?.cancel()
        menuJobs[activity] = activity.lifecycleScope.launch {
            // A detail screen or another client may have changed the state since this card loaded.
            attempt { server.detail(item.id).item }
                .onSuccess { if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) showActions(activity, server, it, onChanged) }
                .onFailure { Toast.makeText(activity, activity.getString(R.string.load_failed, it.readableMessage()), Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showActions(activity: FragmentActivity, server: MediaServer, item: MediaItem, onChanged: (MediaItem) -> Unit) {
        val actions = buildList<Pair<String, () -> Unit>> {
            add(activity.getString(R.string.open_detail) to { activity.startActivity(DetailActivity.intent(activity, item.id)) })
            val markLabel = if (item.watched) R.string.mark_unwatched else R.string.mark_watched
            add(activity.getString(markLabel) to { toggleWatched(activity, server, item, onChanged) })
            if (server.supportsFavorites && item.canFavorite) {
                val favoriteLabel = if (item.favorite) R.string.remove_favorite else R.string.add_favorite
                add(activity.getString(favoriteLabel) to { toggleFavorite(activity, server, item, onChanged) })
            }
            if (item.type == MediaType.EPISODE && item.grandparentId != null) {
                add(activity.getString(R.string.go_to_show) to {
                    activity.startActivity(DetailActivity.intent(activity, item.grandparentId!!, seasonId = item.parentId))
                })
            }
        }
        OptionsPanel(activity, item.cardTitle(), actions.map { Option(it.first) }) { actions[it].second() }.show()
    }

    fun toggleFavorite(activity: FragmentActivity, server: MediaServer, item: MediaItem, onChanged: (MediaItem) -> Unit) {
        if (!server.supportsFavorites || !item.canFavorite) return
        activity.lifecycleScope.launch {
            attempt { server.setFavorite(item.id, !item.favorite) }
                .onSuccess { onChanged(item.copy(favorite = !item.favorite)) }
                .onFailure { Toast.makeText(activity, activity.getString(R.string.load_failed, it.readableMessage()), Toast.LENGTH_SHORT).show() }
        }
    }

    fun toggleWatched(activity: FragmentActivity, server: MediaServer, item: MediaItem, onChanged: (MediaItem) -> Unit) {
        val watched = !item.watched
        val session = WatchNextManager.captureSession(server)
        activity.lifecycleScope.launch {
            attempt { server.setWatched(item.id, watched) }
                .onSuccess {
                    if (watched) WatchNextManager.removeWatched(activity.applicationContext, item, session)
                    onChanged(item.withWatched(watched))
                }
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

/** Only types exposed by the favorites page can be favorited. */
val MediaItem.canFavorite: Boolean
    get() = type == MediaType.MOVIE || type == MediaType.SHOW || type == MediaType.EPISODE

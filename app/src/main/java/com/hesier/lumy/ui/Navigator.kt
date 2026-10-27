package com.hesier.lumy.ui

import android.content.Context
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.ui.detail.DetailActivity
import com.hesier.lumy.ui.list.ItemListActivity

/** 点击卡片后去哪：影片/剧集进详情，合集/播放列表进列表页 */
object Navigator {
    fun open(context: Context, item: MediaItem, libraryId: String? = null) {
        val intent = when (item.type) {
            MediaType.COLLECTION, MediaType.PLAYLIST -> ItemListActivity.container(context, item)
            MediaType.PERSON -> libraryId?.let { ItemListActivity.person(context, item, it) } ?: return
            else -> DetailActivity.intent(context, item.id)
        }
        context.startActivity(intent)
    }
}

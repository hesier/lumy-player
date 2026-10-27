package com.hesier.lumy.ui.home

import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer

/** HomeActivity 提供给内容页（首页、媒体库、搜索）的能力 */
interface HomeHost {
    val server: MediaServer
    fun showBackdrop(item: MediaItem?)
    fun openItem(item: MediaItem)
    /** 长按卡片的操作菜单 */
    fun showItemMenu(item: MediaItem)
    /** 服务器连不上时重新探测地址 */
    fun reconnect()
}

/** 标记已看等操作后通知当前内容页刷新 */
interface ItemChangeListener {
    fun onItemChanged(item: MediaItem)
}

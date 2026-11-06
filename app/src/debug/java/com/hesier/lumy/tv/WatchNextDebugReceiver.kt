package com.hesier.lumy.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.LumyApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.reflect.Proxy

/**
 * Debug-only receiver to easily push or clear Watch Next demo cards via `adb shell am broadcast`.
 */
class WatchNextDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_PUSH -> {
                        val title = intent.getStringExtra("title") ?: "Cyberpunk: Edgerunners"
                        val episode = intent.getStringExtra("episode") ?: "第 4 集 · 幸运儿"
                        val duration = intent.getLongExtra("duration", 1_440_000L)
                        val position = intent.getLongExtra("position", 720_000L)
                        val poster = intent.getStringExtra("poster") ?: "https://images.unsplash.com/photo-1578632767115-351597cf2477?w=640"

                        val session = LumyApp.instance.currentEntry?.session ?: return@launch
                        val fakeServer = Proxy.newProxyInstance(
                            MediaServer::class.java.classLoader,
                            arrayOf(MediaServer::class.java),
                        ) { _, method, args ->
                            when (method.name) {
                                "getSession" -> session
                                "imageUrl" -> (args?.getOrNull(0) as? String) ?: poster
                                else -> null
                            }
                        } as MediaServer

                        val multi = intent.getBooleanExtra("multi", false)
                        val items = if (multi) {
                            listOf(
                                MediaItem(
                                    id = "demo-item-1",
                                    type = MediaType.EPISODE,
                                    title = "第 4 集 · 幸运儿",
                                    showTitle = "Cyberpunk: Edgerunners",
                                    seasonNumber = 1,
                                    episodeNumber = 4,
                                    durationMs = 1_440_000L,
                                    viewOffsetMs = 720_000L,
                                    poster = "https://images.unsplash.com/photo-1578632767115-351597cf2477?w=640",
                                    backdrop = "https://images.unsplash.com/photo-1578632767115-351597cf2477?w=640",
                                    summary = "在夜之城的边缘游走，一次高风险的任务等待着他们。",
                                ),
                                MediaItem(
                                    id = "demo-item-2",
                                    type = MediaType.MOVIE,
                                    title = "奥本海默 (Oppenheimer)",
                                    durationMs = 10_800_000L,
                                    viewOffsetMs = 3_780_000L,
                                    poster = "https://images.unsplash.com/photo-1440404653325-ab127d49abc1?w=640",
                                    backdrop = "https://images.unsplash.com/photo-1440404653325-ab127d49abc1?w=640",
                                    summary = "聚焦于“原子弹之父”罗伯特·奥本海默的主导历程。",
                                ),
                                MediaItem(
                                    id = "demo-item-3",
                                    type = MediaType.MOVIE,
                                    title = "星际穿越 (Interstellar)",
                                    durationMs = 10_140_000L,
                                    viewOffsetMs = 7_098_000L,
                                    poster = "https://images.unsplash.com/photo-1506703719100-a0f3a48c0f86?w=640",
                                    backdrop = "https://images.unsplash.com/photo-1506703719100-a0f3a48c0f86?w=640",
                                    summary = "一组宇航员通过虫洞穿越星际寻找人类新家园。",
                                ),
                            )
                        } else {
                            listOf(
                                MediaItem(
                                    id = intent.getStringExtra("id") ?: "demo-item-1",
                                    type = MediaType.EPISODE,
                                    title = episode,
                                    showTitle = title,
                                    seasonNumber = 1,
                                    episodeNumber = 4,
                                    durationMs = duration,
                                    viewOffsetMs = position,
                                    poster = poster,
                                    backdrop = poster,
                                    summary = "在夜之城的边缘游走，一次高风险的任务等待着他们。",
                                )
                            )
                        }
                        WatchNextManager.syncContinueWatching(context.applicationContext, fakeServer, items)
                    }
                    ACTION_CLEAR -> {
                        WatchNextManager.clearAll(context.applicationContext)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_PUSH = "com.hesier.lumy.debug.WATCH_NEXT_PUSH"
        const val ACTION_CLEAR = "com.hesier.lumy.debug.WATCH_NEXT_CLEAR"
    }
}

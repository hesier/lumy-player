package com.hesier.lumy.ui

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.R
import com.hesier.lumy.core.HubStyle
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.core.MediaServer
import com.hesier.lumy.core.MediaType
import com.hesier.lumy.ui.settings.SettingsActivity
import com.hesier.lumy.ui.widget.CardAdapter
import java.io.File
import java.lang.reflect.Proxy

/** Exercises production cards with isolated playback states and real D-pad input. */
internal class CardPlaybackUiTest(private val instrumentation: Instrumentation) {
    fun runAll() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        lateinit var row: RecyclerView
        lateinit var adapter: CardAdapter
        val items = listOf(
            MediaItem("unwatched", MediaType.EPISODE, "Unwatched", durationMs = 2_700_000),
            MediaItem("playing", MediaType.EPISODE, "In progress", durationMs = 2_700_000, viewOffsetMs = 600_000),
            MediaItem("watched", MediaType.EPISODE, "Watched", durationMs = 2_700_000, watched = true),
        )
        try {
            main {
                val root = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 48, 48, 48)
                }
                adapter = CardAdapter(server(), HubStyle.LANDSCAPE, {}, {})
                row = RecyclerView(activity).apply {
                    layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
                    this.adapter = adapter
                    clipChildren = false
                    clipToPadding = false
                }
                root.addView(row)
                activity.setContentView(root)
                adapter.submit(items)
            }
            await { activity.hasWindowFocus() && row.findViewHolderForAdapterPosition(0) != null }
            main { row.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus() }
            for (position in listOf(1, 2, 1, 0)) {
                instrumentation.sendKeyDownUpSync(if (position > focusedPosition(row)) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                await { row.focusedChild?.let(row::getChildAdapterPosition) == position }
            }
            main {
                val card = row.findViewHolderForAdapterPosition(0)!!.itemView
                adapter.update(items[0].copy(watched = true))
                check(card.hasFocus())
            }
            await {
                val card = row.findViewHolderForAdapterPosition(0)!!.itemView
                card.hasFocus() && card.findViewById<View>(R.id.watched_badge).visibility == View.VISIBLE
            }
            instrumentation.waitForIdleSync()
            main { capture(activity) }
        } finally { main { activity.finish() } }
    }

    private fun capture(activity: SettingsActivity) {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            File(activity.cacheDir, "card-playback-states.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    private fun focusedPosition(row: RecyclerView): Int {
        var position = -1
        main { position = row.focusedChild?.let(row::getChildAdapterPosition) ?: -1 }
        return position
    }

    private fun server(): MediaServer = Proxy.newProxyInstance(
        MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java),
    ) { _, method, _ -> error("Unexpected server call: ${method.name}") } as MediaServer

    private fun main(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        result!!.getOrThrow()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            Thread.sleep(30)
        }
        error("Timed out waiting for card focus/layout")
    }
}

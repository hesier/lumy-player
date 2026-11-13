package com.hesier.lumy.ui.library

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.core.*
import com.hesier.lumy.data.SavedServer
import com.hesier.lumy.ui.home.HomeActivity
import java.lang.reflect.Proxy

/** Exercises grid boundaries with a letter index taller than the media rows. */
class LibraryNavigationTest(private val instrumentation: Instrumentation) {
    private lateinit var home: HomeActivity
    private var itemCount = 16

    fun runAll(): String {
        instrumentation.waitForIdleSync()
        val app = LumyApp.instance
        val serverField = LumyApp::class.java.getDeclaredField("server").apply { isAccessible = true }
        val oldServer = serverField.get(app)
        val oldId = app.store.currentId
        val oldScale = app.settings.uiScale
        val oldWatch = app.settings.watchNextEnabled
        val server = fixture()
        val entry = SavedServer("library-navigation-fixture", server.session)
        try {
            main {
                app.settings.uiScale = 0.8f
                app.settings.watchNextEnabled = false
                app.store.add(entry)
                app.store.currentId = entry.id
                serverField.set(app, server)
            }
            instrumentation.setInTouchMode(false)
            home = instrumentation.startActivitySync(Intent(instrumentation.targetContext, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as HomeActivity
            for (count in listOf(16, 15, 1)) {
                itemCount = count
                checkGrid()
            }
        } finally {
            main {
                if (::home.isInitialized) home.finishAndRemoveTask()
                app.store.remove(entry.id)
                app.store.currentId = oldId
                serverField.set(app, oldServer)
                app.settings.uiScale = oldScale
                app.settings.watchNextEnabled = oldWatch
            }
        }
        return "Library full, partial and single-item last rows, first-row Up, letter-index access and sidebar focus restoration passed"
    }

    private fun checkGrid() {
        main {
            home.supportFragmentManager.beginTransaction().replace(R.id.content,
                LibraryFragment.newInstance(Library("library-fixture", "Fixture", MediaType.MOVIE))).commitNow()
        }
        val grid = main { home.findViewById<RecyclerView>(R.id.grid) }
        await("grid loaded") { grid.adapter?.itemCount == itemCount && grid.childCount > 0 }
        val last = itemCount - 1
        main { grid.scrollToPosition(last) }
        await("last card attached") { grid.findViewHolderForAdapterPosition(last) != null }
        main { check(grid.findViewHolderForAdapterPosition(last)!!.itemView.requestFocus()) }
        repeat(2) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        main { check(grid.findViewHolderForAdapterPosition(last)!!.itemView.hasFocus()) { "Down escaped the last row" } }
        if (itemCount == 16) checkLetters(grid)
        main { grid.scrollToPosition(0) }
        await("first card attached") { grid.findViewHolderForAdapterPosition(0) != null }
        main { grid.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_UP)
        main { check(home.findViewById<ViewGroup>(R.id.filters).hasFocus()) { "Up skipped filters" } }
        main { grid.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        main { check(home.findViewById<View>(R.id.sidebar).hasFocus()) }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        main { check(grid.findViewHolderForAdapterPosition(0)!!.itemView.hasFocus()) }
    }

    private fun checkLetters(grid: RecyclerView) {
        main { check((grid.layoutManager as GridLayoutManager).spanCount == 8) }
        await("letter index loaded") { home.findViewById<ViewGroup>(R.id.letters).childCount == itemCount }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        main { check(home.findViewById<View>(R.id.letters).hasFocus()) { "Right cannot enter letter index" } }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        main { check(grid.hasFocus()) { "Left cannot return to grid" } }
    }

    private fun fixture(): MediaServer {
        val session = ServerSession(ServerKind.PLEX, "library-navigation", "Fixture", "http://127.0.0.1", "")
        return Proxy.newProxyInstance(MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java)) { _, method, _ ->
            when (method.name) {
                "getSession" -> session
                "getSupportsFavorites", "getEmbedsSubtitles" -> false
                "isReachable" -> true
                "homeHubs", "libraries", "playlists", "collections", "genres" -> emptyList<Any>()
                "letterIndex" -> (0 until itemCount).map { LetterBucket(('A' + it).toString(), 1) }
                "libraryItems" -> Page((0 until itemCount).map { MediaItem("card-$it", MediaType.MOVIE, "Card $it") }, 0, itemCount)
                else -> error("Unexpected fixture call ${method.name}")
            }
        } as MediaServer
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 7_000
        while (!main(condition)) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out: $label" }
            SystemClock.sleep(50)
        }
    }

    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}

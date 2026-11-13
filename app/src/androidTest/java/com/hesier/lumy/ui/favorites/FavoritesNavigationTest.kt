package com.hesier.lumy.ui.favorites

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

/** Checks favorites focus with native D-pad events and an isolated server fixture. */
class FavoritesNavigationTest(private val instrumentation: Instrumentation) {
    private lateinit var home: HomeActivity
    private lateinit var grid: RecyclerView
    private lateinit var categories: ViewGroup
    private val movies = (0 until 24).map { MediaItem("movie-$it", MediaType.MOVIE, "Movie $it", favorite = true) }
    private val shows = (0 until 3).map { MediaItem("show-$it", MediaType.SHOW, "Show $it", favorite = true) }

    fun runAll(): String {
        instrumentation.waitForIdleSync()
        val app = LumyApp.instance
        val field = LumyApp::class.java.getDeclaredField("server").apply { isAccessible = true }
        val oldServer = field.get(app)
        val oldId = app.store.currentId
        val oldWatch = app.settings.watchNextEnabled
        val server = fixture()
        val entry = SavedServer("favorites-navigation-fixture", server.session)
        try {
            main {
                app.settings.watchNextEnabled = false
                app.store.add(entry)
                app.store.currentId = entry.id
                field.set(app, server)
            }
            instrumentation.setInTouchMode(false)
            home = instrumentation.startActivitySync(Intent(instrumentation.targetContext, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as HomeActivity
            main {
                home.supportFragmentManager.beginTransaction().replace(R.id.content, FavoritesFragment()).commitNow()
                grid = home.findViewById(R.id.grid)
                categories = home.findViewById(R.id.categories)
            }
            await("favorites loaded") { grid.adapter?.itemCount == movies.size && grid.childCount > 0 }
            checkFirstRow()
            checkScrolledReturn()
            checkCategories()
            checkSidebar()
        } finally {
            main {
                if (::home.isInitialized) home.finishAndRemoveTask()
                app.store.remove(entry.id)
                app.store.currentId = oldId
                field.set(app, oldServer)
                app.settings.watchNextEnabled = oldWatch
            }
        }
        return "Favorites: first-row focus memory, scrolled Back/down restoration, category reset, empty category and sidebar restoration passed"
    }

    private fun checkFirstRow() {
        val columns = main { (grid.layoutManager as GridLayoutManager).spanCount }
        focusPosition(0)
        for (position in 0 until columns) {
            if (position > 0) key(KeyEvent.KEYCODE_DPAD_RIGHT)
            checkPosition(position)
            repeat(3) {
                key(KeyEvent.KEYCODE_DPAD_UP)
                main { check(categories.getChildAt(0).hasFocus()) { "Up did not reach current category" } }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                checkPosition(position)
            }
        }
    }

    private fun checkScrolledReturn() {
        val position = movies.lastIndex
        focusPosition(position)
        val offset = main { grid.computeVerticalScrollOffset() }
        repeat(3) {
            key(KeyEvent.KEYCODE_BACK)
            main { check(categories.getChildAt(0).hasFocus()) }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            checkPosition(position)
            main { check(offset == grid.computeVerticalScrollOffset()) { "Reentry shifted the grid" } }
        }
    }

    private fun checkCategories() {
        key(KeyEvent.KEYCODE_BACK)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("show category loaded") { grid.adapter?.itemCount == shows.size }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        checkPosition(0)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        checkPosition(1)
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        checkPosition(1)
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("empty episode category loaded") { grid.adapter?.itemCount == 0 }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        main { check(categories.getChildAt(2).hasFocus()) { "Empty category lost focus" } }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        await("shows reloaded") { grid.adapter?.itemCount == shows.size }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        checkPosition(0)
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        await("movies reloaded") { grid.adapter?.itemCount == movies.size }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        checkPosition(0)
    }

    private fun checkSidebar() {
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        main { check(home.findViewById<View>(R.id.sidebar).hasFocus()) }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        checkPosition(0)
    }

    private fun focusPosition(position: Int) {
        main { grid.scrollToPosition(position) }
        await("card $position attached") { grid.findViewHolderForAdapterPosition(position) != null }
        main { check(grid.findViewHolderForAdapterPosition(position)!!.itemView.requestFocus()) }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
    }

    private fun checkPosition(expected: Int) = main {
        val actual = home.currentFocus?.let { grid.findContainingViewHolder(it)?.bindingAdapterPosition } ?: -1
        check(expected == actual) {
            "Expected favorite $expected, actual $actual; focus=${home.currentFocus}; " +
                "category=${categories.indexOfChild(categories.focusedChild)}, count=${grid.adapter?.itemCount}"
        }
    }

    private fun fixture(): MediaServer = Proxy.newProxyInstance(
        MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getSession" -> ServerSession(ServerKind.PLEX, "favorites-navigation", "Fixture", "http://127.0.0.1", "")
            "getSupportsFavorites", "isReachable" -> true
            "getEmbedsSubtitles" -> false
            "homeHubs", "libraries", "playlists" -> emptyList<Any>()
            "favorites" -> {
                val items = when (args!![0]) {
                    MediaType.MOVIE -> movies
                    MediaType.SHOW -> shows
                    else -> emptyList()
                }
                val offset = args[1] as Int
                Page(items.drop(offset).take(args[2] as Int), offset, items.size)
            }
            else -> error("Unexpected favorites fixture call ${method.name}")
        }
    } as MediaServer

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100)
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
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

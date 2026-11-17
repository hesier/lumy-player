package com.hesier.lumy.ui.search

import android.app.Instrumentation
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.core.*
import com.hesier.lumy.data.SavedServer
import com.hesier.lumy.ui.detail.DetailActivity
import com.hesier.lumy.ui.home.HomeActivity
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/** Uses real windows and remote keys; no preview result is reused as a full page. */
class SearchResultsNavigationTest(private val instrumentation: Instrumentation) {
    private lateinit var home: HomeActivity
    private var results: SearchResultsActivity? = null
    private var detail: DetailActivity? = null
    private var oldRequested = false
    private var oldDelivered = false
    private var failPage = true
    private val offsets = mutableListOf<Int>()
    private val scope = SearchScope(MediaType.MOVIE, "fixture-library")

    fun runAll(): String {
        val app = LumyApp.instance
        val field = LumyApp::class.java.getDeclaredField("server").apply { isAccessible = true }
        val original = field.get(app)
        val oldId = app.store.currentId
        val oldWatch = app.settings.watchNextEnabled
        val server = fixture()
        val entry = SavedServer("search-navigation-fixture", server.session)
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
            main { home.supportFragmentManager.beginTransaction().replace(R.id.content, SearchFragment()).commitNow() }
            checkStaleSearch()
            checkPagesAndReturn()
        } finally {
            main {
                detail?.finish()
                results?.finish()
                if (::home.isInitialized) home.finishAndRemoveTask()
                app.store.remove(entry.id)
                app.store.currentId = oldId
                field.set(app, original)
                app.settings.watchNextEnabled = oldWatch
            }
        }
        return "Search preview 20 / full 135, page retry, stale responses, D-pad boundaries and detail/preview return focus passed"
    }

    private fun checkStaleSearch() {
        val query = main { home.findViewById<EditText>(R.id.query) }
        main { query.setText("old") }
        await("old search started") { oldRequested }
        main { query.setText("new") }
        await("new preview") { previewTitle() == "new 0" }
        await("old response delivered") { oldDelivered }
        main { check("new 0" == previewTitle()) { "Old response replaced newer search" } }
        main { query.setText("") }
        await("cleared search") { home.findViewById<RecyclerView>(R.id.rows).adapter == null }
        main { query.setText("new") }
        await("preview ready") { previewTitle() == "new 0" }
        // Dismiss the IME before exercising the remote-only result path.
        key(KeyEvent.KEYCODE_BACK)
    }

    private fun checkPagesAndReturn() {
        val rows = main { home.findViewById<RecyclerView>(R.id.rows) }
        val preview = main { rows.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<RecyclerView>(R.id.items) }
        main {
            check(20 == preview.adapter?.itemCount)
            preview.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus()
        }
        key(KeyEvent.KEYCODE_DPAD_UP)
        val all = main { rows.findViewHolderForAdapterPosition(0)!!.itemView.findViewWithTag<View>(R.id.view_all) }
        main { check(all.hasFocus()) { "View all cannot be reached from preview" } }
        val monitor = instrumentation.addMonitor(SearchResultsActivity::class.java.name, null, false)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        results = instrumentation.waitForMonitorWithTimeout(monitor, 5_000) as? SearchResultsActivity
        instrumentation.removeMonitor(monitor)
        checkNotNull(results)
        val grid = main { results!!.findViewById<RecyclerView>(R.id.grid) }
        await("first full page") { grid.adapter?.itemCount == 60 && grid.hasFocus() }
        focus(grid, 0)
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        main { check(grid.findViewHolderForAdapterPosition(0)!!.itemView.hasFocus()) }
        focus(grid, 59)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        main { check(grid.findViewHolderForAdapterPosition(59)!!.itemView.hasFocus()) }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        val more = main { results!!.findViewById<TextView>(R.id.more) }
        main { check(more.hasFocus()) { "Down cannot reach load more" } }
        loadRemainingPages(grid, more)
        checkDetailReturn(grid)
        key(KeyEvent.KEYCODE_BACK)
        await("preview return focus") { home.hasWindowFocus() && all.hasFocus() }
        main {
            check("new" == home.findViewById<EditText>(R.id.query).text.toString())
            check(20 == preview.adapter?.itemCount)
        }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        main { check(preview.findViewHolderForAdapterPosition(0)!!.itemView.hasFocus()) }
    }

    private fun loadRemainingPages(grid: RecyclerView, more: TextView) {
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("page error retry") { more.text == results!!.getString(R.string.retry) }
        main { check(60 == grid.adapter?.itemCount) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("second page") { grid.adapter?.itemCount == 120 && grid.hasFocus() }
        main { more.requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("last page") { grid.adapter?.itemCount == 135 && more.visibility == View.GONE }
        main { check(listOf(0, 60, 60, 120) == offsets) { offsets } }
        focus(grid, 134)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        main { check(grid.findViewHolderForAdapterPosition(134)!!.itemView.hasFocus()) }
    }

    private fun checkDetailReturn(grid: RecyclerView) {
        focus(grid, 99)
        val before = main { (grid.layoutManager as androidx.recyclerview.widget.GridLayoutManager).findFirstVisibleItemPosition() }
        val monitor = instrumentation.addMonitor(DetailActivity::class.java.name, null, false)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        detail = instrumentation.waitForMonitorWithTimeout(monitor, 5_000) as? DetailActivity
        instrumentation.removeMonitor(monitor)
        checkNotNull(detail)
        await("detail window") { detail!!.hasWindowFocus() }
        key(KeyEvent.KEYCODE_BACK)
        await("detail return focus") { results!!.hasWindowFocus() && grid.findViewHolderForAdapterPosition(99)?.itemView?.hasFocus() == true }
        main {
            check(before == (grid.layoutManager as androidx.recyclerview.widget.GridLayoutManager).findFirstVisibleItemPosition())
            check(135 == grid.adapter?.itemCount)
        }
    }

    private fun fixture(): MediaServer {
        val session = ServerSession(ServerKind.PLEX, "search-fixture", "Fixture", "http://127.0.0.1", "")
        return Proxy.newProxyInstance(MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java)) { _, method, args ->
            when (method.name) {
                "getSession" -> session
                "getSupportsFavorites", "getEmbedsSubtitles" -> false
                "isReachable" -> true
                "homeHubs", "libraries", "playlists", "collections", "similar", "children" -> emptyList<Any>()
                "search" -> {
                    val query = args!![0] as String
                    val hubs = listOf(Hub("search", "Movies", HubStyle.POSTER, (0 until 20).map { item(it, query) }, searchScope = scope))
                    if (query == "old") {
                        oldRequested = true
                        @Suppress("UNCHECKED_CAST")
                        val continuation = args.last() as Continuation<List<Hub>>
                        Handler(Looper.getMainLooper()).postDelayed({
                            oldDelivered = true
                            continuation.resumeWith(Result.success(hubs))
                        }, 1_600)
                        COROUTINE_SUSPENDED
                    } else hubs
                }
                "searchItems" -> {
                    check("new" == args!![0])
                    check(scope == args[1])
                    val offset = args[2] as Int
                    val limit = args[3] as Int
                    check(60 == limit)
                    offsets += offset
                    if (offset == 60 && failPage) {
                        failPage = false
                        error("fixture page failure")
                    }
                    Page((offset until minOf(offset + limit, 135)).map { item(it) }, offset, 135)
                }
                "detail" -> MediaDetail(item((args!![0] as String).removePrefix("card-").toInt()))
                else -> error("Unexpected fixture call ${method.name}")
            }
        } as MediaServer
    }

    private fun item(index: Int, query: String = "new") = MediaItem("card-$index", MediaType.MOVIE, "$query $index")

    private fun previewTitle(): String? {
        val row = home.findViewById<RecyclerView>(R.id.rows).findViewHolderForAdapterPosition(0)?.itemView ?: return null
        return row.findViewById<RecyclerView>(R.id.items).findViewHolderForAdapterPosition(0)?.itemView
            ?.findViewById<TextView>(R.id.title)?.text?.toString()
    }

    private fun focus(grid: RecyclerView, position: Int) {
        main { grid.scrollToPosition(position) }
        await("card $position attached") { grid.findViewHolderForAdapterPosition(position) != null }
        main { check(grid.findViewHolderForAdapterPosition(position)!!.itemView.requestFocus()) }
        instrumentation.waitForIdleSync()
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
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

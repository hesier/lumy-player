package com.hesier.lumy.ui.servers

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.ProgressBar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.plex.PlexClient
import com.hesier.lumy.plex.PlexClientInfo
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Keeps account discovery and failing connection probes entirely in-process. */
class ServersNavigationTest(private val instrumentation: Instrumentation) {
    private lateinit var activity: ServersActivity
    private lateinit var list: RecyclerView
    @Volatile private var probeStarted = CountDownLatch(1)
    @Volatile private var finishProbe = CountDownLatch(1)

    fun runAll(): String {
        instrumentation.waitForIdleSync()
        val app = LumyApp.instance
        val account = app.plexAccount
        val resolver = app.plexResolver
        val accountClient = account.javaClass.getDeclaredField("client").apply { isAccessible = true }
        val resolverClient = resolver.javaClass.getDeclaredField("client").apply { isAccessible = true }
        val oldAccount = accountClient.get(account)
        val oldResolver = resolverClient.get(resolver)
        val oldServers = app.store.servers
        val oldId = app.store.currentId
        val client = fixture()
        try {
            main { accountClient.set(account, client); resolverClient.set(resolver, client) }
            instrumentation.setInTouchMode(false)
            activity = instrumentation.startActivitySync(ServersActivity.intent(instrumentation.targetContext, "fixture-token")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as ServersActivity
            list = main { activity.findViewById(R.id.list) }
            await("server list loaded") { list.adapter?.itemCount == SERVER_COUNT && list.childCount > 0 }
            checkFailedConnection(0)
            checkFailedConnection(12)
            checkFailedConnection(12, scrollAway = true)
            main { check(oldServers == app.store.servers && oldId == app.store.currentId) { "Failure changed saved sessions" } }
        } finally {
            finishProbe.countDown()
            main {
                if (::activity.isInitialized) activity.finishAndRemoveTask()
                accountClient.set(account, oldAccount)
                resolverClient.set(resolver, oldResolver)
            }
        }
        return "Failed server connections restore first, scrolled and off-screen cards without changing saved sessions"
    }

    private fun checkFailedConnection(position: Int, scrollAway: Boolean = false) {
        probeStarted = CountDownLatch(1)
        finishProbe = CountDownLatch(1)
        main { (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0) }
        await("selected server attached") { list.findViewHolderForAdapterPosition(position) != null }
        main { check(list.findViewHolderForAdapterPosition(position)!!.itemView.requestFocus()) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        check(probeStarted.await(2, TimeUnit.SECONDS)) { "Connection probe did not start" }
        if (scrollAway) {
            main { (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0) }
            await("first server attached") { list.findViewHolderForAdapterPosition(0) != null }
            main { list.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus() }
            await("original server off-screen") { list.findViewHolderForAdapterPosition(position) == null }
        } else {
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            main { check(!list.findViewHolderForAdapterPosition(position)!!.itemView.hasFocus()) }
        }
        finishProbe.countDown()
        await("failed connection restores server $position") {
            activity.findViewById<ProgressBar>(R.id.progress).visibility == View.GONE &&
                list.findViewHolderForAdapterPosition(position)?.itemView?.hasFocus() == true
        }
    }

    private fun fixture(): PlexClient {
        val resources = (0 until SERVER_COUNT).joinToString(prefix = "[", postfix = "]") {
            """{"name":"Server ${it.toString().padStart(2, '0')}","clientIdentifier":"fixture-$it","provides":"server","accessToken":"fixture-token","connections":[{"protocol":"http","uri":"http://127.0.0.1/focus-fixture","local":true}]}"""
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (code, body) = when (request.url.encodedPath) {
                "/api/v2/resources" -> 200 to resources
                "/focus-fixture/identity" -> {
                    val gate = finishProbe
                    probeStarted.countDown()
                    check(gate.await(5, TimeUnit.SECONDS)) { "Connection probe was not released" }
                    503 to "{}"
                }
                else -> error("Unexpected fixture request ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("Fixture").body(body.toResponseBody()).build()
        }.build()
        return PlexClient(PlexClientInfo("Fixture", "1", "server-navigation", "31", "TV"), http)
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
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

    private companion object {
        const val SERVER_COUNT = 20
    }
}

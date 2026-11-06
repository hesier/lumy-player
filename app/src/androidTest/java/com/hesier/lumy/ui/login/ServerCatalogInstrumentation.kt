package com.hesier.lumy.ui.login

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import com.hesier.lumy.core.LibraryQuery
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.data.SessionStore
import com.hesier.lumy.fnos.FnOsClient
import com.hesier.lumy.fnos.FnOsClientInfo
import com.hesier.lumy.fnos.FnOsMediaServer
import com.hesier.lumy.jellyfin.JellyfinClient
import com.hesier.lumy.jellyfin.JellyfinClientInfo
import com.hesier.lumy.jellyfin.JellyfinMediaServer
import kotlinx.coroutines.runBlocking

/** Read-only contract checks against saved servers; never logs account or media identifiers. */
class ServerCatalogInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        try {
            val messages = runBlocking {
                SessionStore(targetContext).servers.filter {
                    it.kind in listOf(ServerKind.FNOS, ServerKind.JELLYFIN, ServerKind.EMBY)
                }.map { saved ->
                    val session = saved.session
                    val device = session.deviceId ?: "catalog-check"
                    if (saved.kind == ServerKind.FNOS) {
                        val server = FnOsMediaServer(session, FnOsClient(FnOsClientInfo("Lumy", "1", device, "TV")))
                        val library = server.libraries().first()
                        val source = server.libraryItems(library.id, 0, 1).items.first()
                        val buckets = server.letterIndex(library.id, LibraryQuery())
                        val firstPage = server.libraryItems(library.id, 0, 1)
                        check(buckets.isNotEmpty() && buckets.sumOf { it.count } == firstPage.total) {
                            "fnOS live index count differs from catalog total"
                        }
                        val detail = server.detail(source.id)
                        val actor = detail.people.first()
                        val works = server.personItems(library.id, actor.id)
                        check(works.isNotEmpty()) { "Native actor works are empty" }
                        check(works.any { it.id == source.id }) { "Native actor works lost their source item" }
                        val similar = server.similar(source.id)
                        check(similar.isNotEmpty() && similar.none { it.id == source.id })
                        val hubs = server.homeHubs()
                        check(hubs.any { it.id == "nextup" }) { "Live episode history did not produce Next Up" }
                        "fnOS live index, actor mapping, local recommendations and Next Up passed"
                    } else {
                        val server = JellyfinMediaServer(session,
                            JellyfinClient(JellyfinClientInfo("Lumy", "1", device, "TV")))
                        val library = server.libraries().first()
                        val page = server.libraryItems(library.id, 0, 1, LibraryQuery())
                        val buckets = server.letterIndex(library.id, LibraryQuery())
                        check(buckets.isNotEmpty()) { "Live letter index is empty" }
                        check(buckets.sumOf { it.count } == page.total) { "Live index count differs from catalog total" }
                        "${saved.kind} live index count matches catalog"
                    }
                }
            }
            result.putString("stream", messages.joinToString("\n", postfix = "\n"))
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", Log.getStackTraceString(error))
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}

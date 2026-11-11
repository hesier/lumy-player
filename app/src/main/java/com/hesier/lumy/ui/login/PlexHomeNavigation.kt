package com.hesier.lumy.ui.login

import android.app.Activity
import com.hesier.lumy.LumyApp
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.data.SavedServer

object PlexHomeNavigation {
    fun open(activity: Activity, entry: SavedServer, startup: Boolean = false) {
        val token = entry.plexHome?.accountToken ?: entry.accountToken ?: return
        activity.startActivity(PlexHomeActivity.intent(activity, token,
            preferredId = entry.session.serverId, replaceId = entry.id, startup = startup))
    }

    /** Gate media deep links as well as the launcher before any user content is requested. */
    fun gate(activity: Activity): Boolean {
        val app = LumyApp.instance
        val entry = app.currentEntry ?: return false
        if (!app.needsPlexHomeSelection(entry)) return false
        open(activity, entry, startup = true)
        activity.finish()
        return true
    }

    fun isAvailable(entry: SavedServer?): Boolean =
        entry?.kind == ServerKind.PLEX && entry.accountToken != null
}

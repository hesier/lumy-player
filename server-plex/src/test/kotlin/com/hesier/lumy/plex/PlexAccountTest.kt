package com.hesier.lumy.plex

import org.junit.Assert.assertEquals
import org.junit.Test

class PlexAccountTest {
    private val account = PlexAccount(PlexClient(PlexClientInfo("Lumy", "1", "abc-123", "12", "Mi Box S")))

    @Test
    fun `link url prefills the short code`() {
        assertEquals("https://www.plex.tv/link/?pin=AB12", account.linkUrl(PlexPin(id = 1, code = "AB12")))
    }
}

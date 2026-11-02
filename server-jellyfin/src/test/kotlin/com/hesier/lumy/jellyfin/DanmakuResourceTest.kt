package com.hesier.lumy.jellyfin

import com.hesier.lumy.core.ServerDanmakuFormat
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import org.junit.Assert.*
import org.junit.Test

class DanmakuResourceTest {
    private val client = JellyfinClient(JellyfinClientInfo("Lumy", "test", "device", "TV"))

    @Test fun jellyfinKeepsReverseProxyPrefixAndLoginHeaders() {
        val server = JellyfinMediaServer(session(ServerKind.JELLYFIN), client)
        val resource = server.danmakuResource("item/with space")
        assertEquals("https://media.example/prefix/api/danmu/item%2Fwith%20space/raw", resource.url)
        assertEquals(ServerDanmakuFormat.XML, resource.format)
        assertTrue(resource.headers.getValue("Authorization").contains("test-token"))
        assertFalse(resource.toString().contains("test-token"))
    }

    @Test fun embyUsesJsonOptionWithSeparateFormat() {
        val server = JellyfinMediaServer(session(ServerKind.EMBY), client)
        val resource = server.danmakuResource("123")
        assertEquals("https://media.example/prefix/api/danmu/123?option=GetJsonById", resource.url)
        assertEquals(ServerDanmakuFormat.EMBY_JSON, resource.format)
        assertEquals("test-token", resource.headers["X-Emby-Token"])
    }

    @Test fun originalTitleAndProviderIdsAreAvailableForMatching() {
        val json = """{"Id":"one","Name":"Local title","OriginalTitle":"Original","ProviderIds":{"Tmdb":"123"}}"""
        val item = client.json.decodeFromString<BaseItem>(json)
        val detail = item.toDetail(emptyList(), null)
        assertEquals("Original", detail.originalTitle)
        assertEquals("123", detail.providerIds["Tmdb"])
    }

    private fun session(kind: ServerKind) = ServerSession(kind, "server", "TV", "https://media.example/prefix",
        "test-token", "user", "device")
}

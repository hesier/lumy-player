package com.hesier.lumy.data

import android.content.Context
import com.hesier.lumy.net.danmaku.DanmakuAnime
import com.hesier.lumy.net.danmaku.DanmakuEpisode
import org.json.JSONObject

/** Two instrumentation processes verify disk persistence without touching user preferences. */
internal class DanmakuMatchMemoryTest(private val context: Context) {
    private val name = "danmaku-match-memory-test"
    private val store = DanmakuStore(context, name)
    private val prefs = context.getSharedPreferences("${name}_matches", Context.MODE_PRIVATE)
    private val choice = DanmakuChoice(DanmakuSourceKind.CUSTOM, "fixture-source")

    fun run(phase: String): String {
        if (phase == "prepare") {
            store.clearMatches()
            for ((key, offset) in listOf("positive" to 1500L, "negative" to -500L, "legacy" to 0L))
                store.remember(key, DanmakuMatch(choice, DanmakuAnime("a", "Memory"),
                    DanmakuEpisode("e", "Episode", "1"), null, 1, 1, offset))
            val key = "match:legacy:${choice.identity}"
            val old = JSONObject(checkNotNull(prefs.getString(key, null))).apply { remove("sourceOffsetMs") }
            check(prefs.edit().putString(key, old.toString()).commit())
            return "Danmaku memory prepared; restart process before verify"
        }
        require(phase == "verify")
        try {
            check(store.match("positive", choice.identity)?.sourceOffsetMs == 1500L)
            check(store.match("negative", choice.identity)?.sourceOffsetMs == -500L)
            check(store.match("legacy", choice.identity)?.sourceOffsetMs == 0L)
            store.restoreAuto("positive")
            check(store.match("positive", choice.identity) == null)
            check(store.match("negative", choice.identity)?.sourceOffsetMs == -500L)
            return "Danmaku positive/negative offsets and legacy records survived process restart; cleanup passed"
        } finally { store.clearMatches(); check(prefs.edit().clear().commit()) }
    }
}

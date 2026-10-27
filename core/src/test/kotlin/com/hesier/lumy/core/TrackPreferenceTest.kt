package com.hesier.lumy.core

import com.hesier.lumy.core.TrackLanguage.YUE
import com.hesier.lumy.core.TrackLanguage.ZH
import com.hesier.lumy.core.TrackLanguage.ZH_HANS
import com.hesier.lumy.core.TrackLanguage.ZH_HANT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackPreferenceTest {

    @Test
    fun recognizesChineseVariants() {
        assertEquals(ZH_HANS, TrackLanguage.of("chi", "简体 (中文 ASS)"))
        assertEquals(ZH_HANS, TrackLanguage.of("chi", "Chinese (Simplified) (SRT)"))
        assertEquals(ZH_HANS, TrackLanguage.of("zho", "CHS"))
        assertEquals(ZH_HANT, TrackLanguage.of("chi", "繁體中文 (PGS)"))
        assertEquals(ZH_HANT, TrackLanguage.of("chi", "中文 (CHT ASS)"))
        assertEquals(ZH, TrackLanguage.of("chi", "简繁双语 (ASS)"))
        assertEquals(ZH, TrackLanguage.of("chi", "中文 (AC3 5.1)"))
        assertEquals(YUE, TrackLanguage.of("chi", "粤语 (AAC 2.0)"))
        assertEquals(YUE, TrackLanguage.of("yue", "Cantonese"))
        // 没有语言代码时看标题
        assertEquals(ZH_HANS, TrackLanguage.of(null, "简体中文"))
    }

    @Test
    fun recognizesScriptInLanguageCode() {
        assertEquals(ZH_HANS, TrackLanguage.of("zh-Hans", "中文"))
        assertEquals(ZH_HANT, TrackLanguage.of("zh-Hant", "Chinese"))
        assertEquals(ZH_HANS, TrackLanguage.of("zh-CN", "Chinese"))
        assertEquals(ZH_HANT, TrackLanguage.of("zh_TW", "Chinese"))
        assertEquals(ZH_HANT, TrackLanguage.of("zh-Hant-HK", "中文"))
        assertEquals(YUE, TrackLanguage.of("zh-yue", "Chinese"))
        assertEquals("eng", TrackLanguage.of("en-US", "English"))
    }

    @Test
    fun scriptFromCodeDecidesWhenTitlesAreSame() {
        val hant = sub("1", "zh-Hant", "中文", selected = true)
        val hans = sub("2", "zh-Hans", "中文")
        assertEquals(hans, TrackPicker.subtitle(listOf(hant, hans), SubtitleRule.Language(ZH_HANS), null)?.stream)
        assertEquals(hant, TrackPicker.subtitle(listOf(hans, hant), SubtitleRule.Language(ZH_HANT), null)?.stream)
    }

    @Test
    fun recognizesOtherLanguages() {
        assertEquals("eng", TrackLanguage.of("eng", "English (SDH SRT)"))
        assertEquals("eng", TrackLanguage.of("en", "English"))
        // "Descriptive" 里有 sc，但不是中文
        assertEquals("eng", TrackLanguage.of("eng", "English Descriptive Audio"))
        assertNull(TrackLanguage.of(null, "Track 3"))
        assertNull(TrackLanguage.of("und", "Unknown"))
    }

    @Test
    fun audioPrefersExactLanguageThenOtherChinese() {
        val eng = audio("1", "eng", "English (TrueHD 7.1)", selected = true)
        val yue = audio("2", "chi", "粤语 (AC3 2.0)")
        val zh = audio("3", "chi", "国语 (AC3 5.1)")
        assertEquals(zh, TrackPicker.audio(listOf(eng, yue, zh), ZH))
        assertEquals(yue, TrackPicker.audio(listOf(eng, yue, zh), YUE))
        assertEquals(yue, TrackPicker.audio(listOf(eng, yue), ZH))
        assertNull(TrackPicker.audio(listOf(eng), ZH))
    }

    @Test
    fun subtitlePrefersRequestedScript() {
        val hant = sub("1", "chi", "繁體 (PGS)", selected = true)
        val hans = sub("2", "chi", "简体 (ASS)")
        val plain = sub("3", "chi", "中文 (SRT)")
        val eng = sub("4", "eng", "English (SRT)")
        val rule = SubtitleRule.Language(ZH_HANS)
        assertEquals(hans, TrackPicker.subtitle(listOf(hant, hans, plain, eng), rule, "eng")?.stream)
        assertEquals(plain, TrackPicker.subtitle(listOf(hant, plain, eng), rule, "eng")?.stream)
        assertEquals(hant, TrackPicker.subtitle(listOf(hant, eng), rule, "eng")?.stream)
        // 没有中文字幕：交给下一级
        assertNull(TrackPicker.subtitle(listOf(eng), rule, "eng"))
    }

    @Test
    fun subtitlePrefersFullOverForced() {
        val forced = sub("1", "chi", "简体 强制", forced = true)
        val full = sub("2", "chi", "简体")
        assertEquals(full, TrackPicker.subtitle(listOf(forced, full), SubtitleRule.Language(ZH_HANS), null)?.stream)
    }

    @Test
    fun forcedOnlyAndOff() {
        val full = sub("1", "chi", "简体")
        val forcedEng = sub("2", "eng", "English Forced", forced = true)
        val forcedZh = sub("3", "chi", "简体 Forced", forced = true)
        val streams = listOf(full, forcedEng, forcedZh)
        assertEquals(forcedZh, TrackPicker.subtitle(streams, SubtitleRule.ForcedOnly(ZH_HANS), "eng")?.stream)
        assertEquals(forcedEng, TrackPicker.subtitle(listOf(full, forcedEng), SubtitleRule.ForcedOnly(ZH_HANS), "eng")?.stream)
        // 没有强制字幕就关闭，而不是交给服务器
        val none = TrackPicker.subtitle(listOf(full), SubtitleRule.ForcedOnly(ZH_HANS), "eng")
        assertNull(none!!.stream)
        assertNull(TrackPicker.subtitle(streams, SubtitleRule.Off, "eng")!!.stream)
    }

    private fun audio(id: String, lang: String?, title: String, selected: Boolean = false) =
        AudioStream(id = id, codec = "ac3", channels = 6, language = lang, title = title, selected = selected)

    private fun sub(id: String, lang: String?, title: String, forced: Boolean = false, selected: Boolean = false) =
        SubtitleStream(id = id, codec = "srt", language = lang, title = title, forced = forced, selected = selected, key = null)
}

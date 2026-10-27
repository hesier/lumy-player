package com.hesier.lumy.core

/**
 * 从语言代码和轨道标题识别语言。
 *
 * 中文细分为 [ZH_HANS] / [ZH_HANT]（字幕的简繁）、[YUE]（粤语）和分不出的 [ZH]：
 * 语言代码是 zh-Hans / zh-TW 这类带文字或地区的标签时按代码判断；
 * 只有 chi/zho 时看标题里的“简体”“CHT”之类的字样。
 * 其他语言用 ISO 639-2 三字母代码（eng、jpn…）。
 */
object TrackLanguage {
    const val ZH = "zh"
    const val ZH_HANS = "zh-Hans"
    const val ZH_HANT = "zh-Hant"
    const val YUE = "yue"

    private val chinese = setOf(ZH, ZH_HANS, ZH_HANT, YUE)
    private val chineseCodes = setOf("chi", "zho", "zh", "chs", "cht", "cmn", "yue")
    private val twoLetter = mapOf("en" to "eng", "ja" to "jpn", "ko" to "kor")
    private val hansTags = setOf("hans", "cn", "sg")
    private val hantTags = setOf("hant", "tw", "hk", "mo")
    private val chineseMarks = listOf("中文", "中字", "国语", "國語", "普通话", "粤", "粵", "简", "簡", "繁")
    private val hansWords = setOf("chs", "sc", "gb", "simplified", "hans", "cn")
    private val hantWords = setOf("cht", "tc", "big5", "traditional", "hant", "tw", "hk")
    private val cantoneseWords = setOf("cantonese", "yue")

    /** 认不出语言时返回 null */
    fun of(code: String?, title: String): String? {
        val tags = code?.lowercase()?.split('-', '_').orEmpty()
        val c = tags.firstOrNull()?.takeIf { it.isNotBlank() && it != "und" }
        val subtags = tags.drop(1)
        val t = title.lowercase()
        // 按单词比较，避免 "Descriptive" 里的 "sc" 被当成简体
        val words = t.split(Regex("[^a-z0-9]+")).toSet()
        val isChinese = if (c != null) c in chineseCodes else chineseMarks.any { it in t }
        if (!isChinese) return c?.let { twoLetter[it] ?: it }
        if (c == "yue" || "yue" in subtags || "粤" in t || "粵" in t || words.any { it in cantoneseWords }) return YUE
        // 代码里写明了简繁就以代码为准
        if (subtags.any { it in hansTags }) return ZH_HANS
        if (subtags.any { it in hantTags }) return ZH_HANT
        val hans = c == "chs" || "简" in t || "簡" in t || words.any { it in hansWords }
        val hant = c == "cht" || "繁" in t || words.any { it in hantWords }
        return when {
            hans && !hant -> ZH_HANS
            hant && !hans -> ZH_HANT
            else -> ZH
        }
    }

    /**
     * 语言 [lang] 满足偏好 [pref] 的程度，越小越好，不满足返回 null。
     * 中文之间互相兜底：想要简体时，没标简繁的比繁体好，繁体又比别的语言好。
     */
    fun rank(pref: String, lang: String?): Int? = when {
        lang == null -> null
        lang == pref -> 0
        pref in chinese && lang in chinese -> when {
            pref == YUE || lang == YUE -> 3
            pref == ZH || lang == ZH -> 1
            else -> 2
        }
        else -> null
    }
}

/** 字幕的选择规则 */
sealed interface SubtitleRule {
    data object Off : SubtitleRule

    /** 只显示强制字幕（外语片段的翻译），优先 [lang]，其次和音轨同语言的 */
    data class ForcedOnly(val lang: String?) : SubtitleRule

    data class Language(val lang: String) : SubtitleRule
}

/** [stream] 为 null 表示关闭 */
class TrackChoice<out T>(val stream: T?)

object TrackPicker {

    /** 最符合 [lang] 的音轨；都不符合返回 null */
    fun audio(streams: List<AudioStream>, lang: String): AudioStream? =
        best(streams, lang, { TrackLanguage.of(it.language, it.title) }, { if (it.selected) 0 else 1 })

    /**
     * 按 [rule] 选字幕，[audioLang] 是选中音轨的语言。
     * 没有符合的字幕返回 null，交给下一级规则 / 服务器的选择。
     */
    fun subtitle(streams: List<SubtitleStream>, rule: SubtitleRule, audioLang: String?): TrackChoice<SubtitleStream>? {
        val lang = { s: SubtitleStream -> TrackLanguage.of(s.language, s.title) }
        return when (rule) {
            SubtitleRule.Off -> TrackChoice(null)
            is SubtitleRule.ForcedOnly -> {
                val forced = streams.filter { it.forced }
                val pick = listOfNotNull(rule.lang, audioLang).firstNotNullOfOrNull { l ->
                    best(forced, l, lang) { if (it.selected) 0 else 1 }
                }
                TrackChoice(pick)
            }
            // 同等匹配时完整字幕优先于强制字幕
            is SubtitleRule.Language -> best(streams, rule.lang, lang) { (if (it.forced) 2 else 0) + (if (it.selected) 0 else 1) }
                ?.let { TrackChoice(it) }
        }
    }

    private fun <T> best(streams: List<T>, pref: String, lang: (T) -> String?, tieBreak: (T) -> Int): T? =
        streams.mapNotNull { s -> TrackLanguage.rank(pref, lang(s))?.let { it to s } }
            .minWithOrNull(compareBy({ it.first }, { tieBreak(it.second) }))
            ?.second
}

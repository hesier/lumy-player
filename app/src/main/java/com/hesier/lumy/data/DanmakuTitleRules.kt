package com.hesier.lumy.data

import java.text.Normalizer
import java.util.Locale

internal object DanmakuTitleRules {
    private val chineseSeason = Regex("第([0-9一二三四五六七八九十零〇两]{1,3})[季期]")
    private val season = Regex("(?i)(?<![\\p{L}\\p{N}])(?:season\\s*(\\d+)|s(\\d+)(?:e\\d+(?:\\.\\d+)?)?)(?![\\p{L}\\p{N}])")
    private val year = Regex("[（(]\\s*((?:19|20)\\d{2})\\s*[)）]")
    private val source = Regex("(?i)\\s*[\\[【](?:bilibili|b站|腾讯视频|爱奇艺|优酷|netflix|web-dl|bluray)[\\]】]\\s*$")
    private val typedSource = Regex("(?i)\\s*【(?:动漫|日番|国漫|动画|電影|电影|劇集|剧集|電視劇|电视剧|国产剧|港剧|台剧|韩剧|日剧|美剧|网络剧)】\\s*from\\s+[\\p{L}\\p{N}_-]+\\s*$")
    private val sourceNote = Regex("[（(]\\s*来源[:：][^()（）]*[)）]\\s*$")
    private val typeBadge = Regex("(?i)\\s*【(?:动漫|日番|国漫|动画|電影|电影|劇集|剧集|電視劇|电视剧|国产剧|港剧|台剧|韩剧|日剧|美剧|网络剧)】\\s*$")
    private val episode = Regex("(?i)(?<![\\p{L}\\p{N}])(?:s(\\d+)e(\\d+(?:\\.\\d+)?)|ep\\s*(\\d+(?:\\.\\d+)?))(?![\\p{L}\\p{N}])|第([0-9]+(?:\\.[0-9]+)?)[集话話]")
    private val special = Regex("(?i)(?<![a-z])(?:sp|ova|oad|special)(?![a-z])|特别篇|特別篇")
    private val trailer = Regex("(?i)(?<![a-z])(?:trailer|pv|teaser)(?![a-z])|预告|預告")

    fun folded(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    fun seasons(value: String): Set<Int> = buildSet {
        val text = folded(value)
        chineseSeason.findAll(text).forEach { match -> chinese(match.groupValues[1])?.let(::add) }
        season.findAll(text).forEach { match ->
            (match.groupValues[1].ifEmpty { match.groupValues[2] }).toIntOrNull()?.let(::add)
        }
    }
    fun years(value: String): Set<Int> = year.findAll(folded(value)).map { it.groupValues[1].toInt() }.toSet()
    fun special(value: String) = special.containsMatchIn(folded(value))
    fun trailer(value: String) = trailer.containsMatchIn(folded(value))
    fun episodeNumbers(value: String): Set<Double> {
        val text = folded(value).trim()
        text.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return setOf(it) }
        return episode.findAll(text).mapNotNull { match ->
            match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }?.toDoubleOrNull()
        }.toSet()
    }
    fun normalized(value: String): String {
        var text = folded(value)
        text = sourceNote.replace(text, "")
        text = typedSource.replace(text, "")
        text = source.replace(text, "")
        text = typeBadge.replace(text, "")
        text = year.replace(text, "")
        text = chineseSeason.replace(text, "")
        text = season.replace(text, "")
        text = episode.replace(text, "")
        return StringBuilder().also { out ->
            points(text).filter { Character.isLetterOrDigit(it) }.forEach { out.appendCodePoint(it) }
        }.toString()
    }
    fun dice(left: String, right: String): Double {
        val a = points(normalized(left))
        val b = points(normalized(right))
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.size < 3 || b.size < 3) return 0.0
        val pairs = a.zipWithNext().groupingBy { it }.eachCount().toMutableMap()
        var overlap = 0
        b.zipWithNext().forEach { pair ->
            val count = pairs[pair] ?: 0
            if (count > 0) { overlap++; pairs[pair] = count - 1 }
        }
        return 2.0 * overlap / (a.size + b.size - 2)
    }
    fun length(value: String) = points(normalized(value)).size
    private fun points(value: String): List<Int> {
        val result = mutableListOf<Int>()
        var index = 0
        while (index < value.length) {
            val point = Character.codePointAt(value, index)
            result += point
            index += Character.charCount(point)
        }
        return result
    }
    private fun chinese(value: String): Int? {
        value.toIntOrNull()?.let { return it }
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3,
            '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (value == "十") return 10
        if ('十' in value) {
            val parts = value.split('十')
            val tens = if (parts[0].isEmpty()) 1 else digits[parts[0].singleOrNull()] ?: return null
            val ones = if (parts[1].isEmpty()) 0 else digits[parts[1].singleOrNull()] ?: return null
            return tens * 10 + ones
        }
        return digits[value.singleOrNull()]
    }
}

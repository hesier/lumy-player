@file:OptIn(UnstableApi::class)

package com.hesier.lumy.ui.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.mkv.MatroskaExtractor
import com.hesier.lumy.util.DebugLog
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser

/**
 * 代替 `withAssMkvSupport`：MKV 用 libass 的解析器，外面再包一层 [ClusterSeekingExtractor]，
 * 让没有索引（Cues）的 MKV 也能跳转。
 */
fun ExtractorsFactory.withSeekableAssMkv(parsers: SubtitleParser.Factory, ass: AssHandler) = ExtractorsFactory {
    createExtractors().map {
        if (it is MatroskaExtractor) ClusterSeekingExtractor(ProbingMatroskaExtractor(parsers, ass)) else it
    }.toTypedArray()
}

/** 记下按簇查找要用的信息：时间单位和第一个簇的位置 */
class ProbingMatroskaExtractor(parsers: SubtitleParser.Factory, ass: AssHandler) : AssMatroskaExtractor(parsers, ass, 0) {
    var timecodeScaleNs = 1_000_000L
        private set
    var firstClusterContentPosition = C.INDEX_UNSET.toLong()
        private set

    override fun integerElement(id: Int, value: Long) {
        if (id == ID_TIMECODE_SCALE) timecodeScaleNs = value
        super.integerElement(id, value)
    }

    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        if (id == ID_CLUSTER && firstClusterContentPosition < 0) firstClusterContentPosition = contentPosition
        super.startMasterElement(id, contentPosition, contentSize)
    }

    companion object {
        const val ID_CLUSTER = 0x1F43B675
        const val ID_TIMECODE_SCALE = 0x2AD7B1
    }
}

/**
 * Media3 只靠 Cues 跳转，没有 Cues 的 MKV（不少演唱会、录制的片源）续播和快进都会回到开头。
 * VLC / Kodi 这类播放器是按比例估一个字节位置，再往后找簇（Cluster）的开头。这里也这么做：
 * 把“不能跳转”的 SeekMap 换成按比例估算的，跳转时按簇的时间戳二分查找，找到目标之前最近的簇，
 * 再从那里交给原来的解析器。
 */
class ClusterSeekingExtractor(private val inner: ProbingMatroskaExtractor) : Extractor {

    private var length = C.LENGTH_UNSET.toLong()
    private var seekMap: EstimatedSeekMap? = null
    private var search: Search? = null
    private val scratch = ByteArray(SCAN_CHUNK)

    override fun sniff(input: ExtractorInput) = inner.sniff(input)

    override fun init(output: ExtractorOutput) {
        inner.init(object : ExtractorOutput by output {
            override fun seekMap(map: SeekMap) = output.seekMap(replaceUnseekable(map))
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        length = input.length
        val s = search ?: return inner.read(input, seekPosition)
        return s.step(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        inner.seek(position, timeUs)
        val map = seekMap
        search = if (map != null && position >= map.start) Search(map, timeUs) else null
    }

    override fun release() = inner.release()

    override fun getUnderlyingImplementation(): Extractor = inner

    private fun replaceUnseekable(map: SeekMap): SeekMap {
        val first = inner.firstClusterContentPosition
        if (map.isSeekable || map.durationUs == C.TIME_UNSET || length == C.LENGTH_UNSET.toLong() || first < 0) return map
        // 簇头最长 12 字节（4 字节 ID + 最长 8 字节长度），从这里往后一定能找到第一个簇
        return EstimatedSeekMap(map.durationUs, (first - 12).coerceAtLeast(0), length).also { seekMap = it }
    }

    private class EstimatedSeekMap(private val durationUs: Long, val start: Long, val end: Long) : SeekMap {
        override fun isSeekable() = true
        override fun getDurationUs() = durationUs
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val pos = start + ((end - start).toDouble() * timeUs / durationUs).toLong()
            return SeekMap.SeekPoints(SeekPoint(timeUs, pos.coerceIn(start, end - 1)))
        }
    }

    private class Cluster(val position: Long, val timeUs: Long, val nextPosition: Long?)

    /**
     * 一次跳转的查找过程。[lo] 是已知不晚于目标的位置（找到簇后就是那个簇的开头），[hi] 是已知晚于目标的位置。
     * 范围足够小后从 [lo] 逐个簇往后走，停在目标之前最后一个簇。读取器从 SeekMap 给的估算位置开始，
     * 第一次直接在那里找。
     */
    private inner class Search(private val map: EstimatedSeekMap, private val targetUs: Long) {
        private var lo = map.start
        private var loUs = 0L
        private var loCluster = false
        private var hi = map.end
        private var hiUs = map.durationUs
        private var walking = false
        private var steps = 0

        fun step(input: ExtractorInput, holder: PositionHolder): Int {
            while (true) {
                if (++steps > MAX_STEPS) return finish(input, holder, if (loCluster) lo else map.start)
                val probe = input.position
                // 块里可能找到 hi 之后的簇，不能拿它把上界往后推
                val c = findCluster(input, if (walking) map.end else hi)?.takeIf { walking || it.position < hi }
                if (walking) {
                    if (c == null || c.timeUs > targetUs) return finish(input, holder, if (loCluster) lo else c?.position ?: map.start)
                    lo = c.position; loUs = c.timeUs; loCluster = true
                    val next = c.nextPosition ?: (c.position + 4)
                    if (next >= map.end) return finish(input, holder, lo)
                    if (!moveTo(input, holder, next)) return Extractor.RESULT_SEEK
                    continue
                }
                when {
                    c == null -> hi = probe
                    c.timeUs <= targetUs -> { lo = c.position; loUs = c.timeUs; loCluster = true }
                    else -> { hi = c.position; hiUs = c.timeUs }
                }
                val next = if (hi - lo <= WALK_RANGE) {
                    walking = true
                    lo
                } else {
                    val ratio = ((targetUs - loUs).toDouble() / (hiUs - loUs).coerceAtLeast(1)).coerceIn(0.0, 1.0)
                    (lo + ((hi - lo) * ratio).toLong()).coerceIn(lo + 1, hi - WALK_RANGE / 2)
                }
                if (!moveTo(input, holder, next)) return Extractor.RESULT_SEEK
            }
        }

        private fun finish(input: ExtractorInput, holder: PositionHolder, position: Long): Int {
            search = null
            DebugLog.i("LumyPlayer", "mkv cluster seek ${targetUs / 1000}ms -> $position (cluster ${loUs / 1000}ms, $steps steps)")
            if (input.position == position) return Extractor.RESULT_CONTINUE
            holder.position = position
            return Extractor.RESULT_SEEK
        }
    }

    /** 往后近的直接跳过，省掉重新发请求；否则让播放器从新位置重新打开。@return 是否已经到位 */
    private fun moveTo(input: ExtractorInput, holder: PositionHolder, position: Long): Boolean {
        val distance = position - input.position
        if (distance in 0..SKIP_LIMIT && input.skipFully(distance.toInt(), true)) return true
        holder.position = position
        return false
    }

    /**
     * 从当前位置往后找簇开头（ID 1F 43 B6 75，后面紧跟簇时间戳），找到时读取位置停在簇开头。
     * 只看 peek 缓冲，没找到的部分跳过，内存占用固定。
     */
    private fun findCluster(input: ExtractorInput, limit: Long): Cluster? {
        while (input.position < limit) {
            input.resetPeekPosition()
            val n = peekUpTo(input, scratch.size)
            if (n < HEADER_PEEK) return null
            for (i in 0..n - HEADER_PEEK) {
                if (scratch[i] != 0x1F.toByte() || scratch[i + 1] != 0x43.toByte() ||
                    scratch[i + 2] != 0xB6.toByte() || scratch[i + 3] != 0x75.toByte()
                ) continue
                val c = parseCluster(input.position + i, i) ?: continue
                input.resetPeekPosition()
                if (i > 0) input.skipFully(i)
                return c
            }
            input.resetPeekPosition()
            // 留下一段尾巴，簇头跨块时下一轮还能找到
            if (!input.skipFully(n - HEADER_PEEK + 1, true)) return null
        }
        return null
    }

    private fun peekUpTo(input: ExtractorInput, max: Int): Int {
        var total = 0
        while (total < max) {
            val r = input.peek(scratch, total, max - total)
            if (r == C.RESULT_END_OF_INPUT) break
            total += r
        }
        return total
    }

    /** 校验簇头：长度字段合法，前几个子元素里有时间戳（之前只允许 CRC-32 / Void） */
    private fun parseCluster(position: Long, offset: Int): Cluster? {
        // 每读一个变长整数前都要留出 8 字节，保证不越过已 peek 的范围
        val end = offset + HEADER_PEEK - 8
        var p = offset + 4
        val (sizeLen, size) = readVint(p) ?: return null
        p += sizeLen
        val unknownSize = size == (1L shl (7 * sizeLen)) - 1
        repeat(3) {
            if (p >= end) return null
            val idLen = vintLength(p) ?: return null
            if (idLen > 4) return null
            var id = 0
            for (k in 0 until idLen) id = (id shl 8) or (scratch[p + k].toInt() and 0xFF)
            p += idLen
            if (p >= end) return null
            val (lenLen, len) = readVint(p) ?: return null
            p += lenLen
            when (id) {
                ID_TIMESTAMP -> {
                    if (len !in 1..8 || p + len > end + 8) return null
                    var ts = 0L
                    for (k in 0 until len.toInt()) ts = (ts shl 8) or (scratch[p + k].toLong() and 0xFF)
                    val timeUs = ts * inner.timecodeScaleNs / 1000
                    if (timeUs < 0 || timeUs > (seekMap?.durationUs ?: Long.MAX_VALUE) + MAX_OVERSHOOT_US) return null
                    val headerLen = 4 + sizeLen
                    return Cluster(position, timeUs, if (unknownSize) null else position + headerLen + size)
                }
                ID_CRC32, ID_VOID -> {
                    if (len > 16) return null
                    p += len.toInt()
                }
                else -> return null
            }
        }
        return null
    }

    private fun vintLength(p: Int): Int? {
        val b = scratch[p].toInt() and 0xFF
        if (b == 0) return null
        return Integer.numberOfLeadingZeros(b) - 23
    }

    /** @return (字节数, 去掉标记位后的值) */
    private fun readVint(p: Int): Pair<Int, Long>? {
        val len = vintLength(p) ?: return null
        var v = (scratch[p].toLong() and 0xFF) and ((1L shl (8 - len)) - 1)
        for (k in 1 until len) v = (v shl 8) or (scratch[p + k].toLong() and 0xFF)
        return len to v
    }

    private companion object {
        const val ID_TIMESTAMP = 0xE7
        const val ID_CRC32 = 0xBF
        const val ID_VOID = 0xEC
        const val SCAN_CHUNK = 64 * 1024
        /** 一个簇头加时间戳（含可选的 CRC-32）用不了这么多字节 */
        const val HEADER_PEEK = 48
        /** 范围小于这个就逐个簇往后走 */
        const val WALK_RANGE = 4L * 1024 * 1024
        /** 往后这么近就直接读过去，不重新发请求 */
        const val SKIP_LIMIT = 1L * 1024 * 1024
        const val MAX_STEPS = 200
        const val MAX_OVERSHOOT_US = 600_000_000L
    }
}

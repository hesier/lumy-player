@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hesier.lumy.ui.player

import androidx.media3.common.C
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.PositionHolder
import java.io.EOFException

/** Finds chained Cues before the main EBML reader has any state, then restores its input. */
internal class MatroskaSeekHeadProbe {
    var cuesRelativePosition: Long? = null
        private set
    private var phase = Phase.EBML
    private var originalPosition = C.INDEX_UNSET.toLong()
    private var cursor = 0L
    private var segment = 0L
    private var bytesRead = 0
    private var headersRead = 0
    private val pending = ArrayDeque<Long>()
    private val visited = HashSet<Long>()

    /** False means an external input reposition is required, without calling the inner seek(). */
    fun read(input: ExtractorInput, holder: PositionHolder): Boolean {
        if (phase == Phase.DONE) return true
        if (originalPosition == C.INDEX_UNSET.toLong()) {
            originalPosition = input.position
            if (input.position != 0L || input.length == C.LENGTH_UNSET.toLong()) phase = Phase.DONE
        }
        try {
            while (phase != Phase.DONE) {
                if (phase == Phase.RESTORE) return restore(input, holder)
                if (++headersRead > MAX_HEADERS || bytesRead > MAX_BYTES) {
                    phase = Phase.RESTORE
                    continue
                }
                if (!step(input, holder)) return false
            }
        } catch (_: EOFException) {
            phase = Phase.RESTORE
        } catch (_: IllegalArgumentException) {
            phase = Phase.RESTORE
        }
        return if (phase == Phase.RESTORE) restore(input, holder) else true
    }

    private fun step(input: ExtractorInput, holder: PositionHolder): Boolean {
        val header = peek(input, cursor, HEADER_BYTES, holder) ?: return false
        val node = element(header, 0)
        val body = cursor + node.content
        when (phase) {
            Phase.EBML -> {
                require(node.id == ID_EBML)
                cursor = checkedEnd(body, node.size, input.length)
                phase = Phase.SEGMENT
            }
            Phase.SEGMENT -> {
                require(node.id == ID_SEGMENT)
                segment = body
                cursor = body
                phase = Phase.HEADERS
            }
            Phase.HEADERS -> readTopLevel(input, holder, node, body)?.let { return it }
            Phase.LINK -> readLinked(input, holder, node, body)?.let { return it }
            Phase.CUES -> {
                if (node.id == ID_CUES) {
                    checkedEnd(body, node.size, input.length)
                    cuesRelativePosition = cursor - segment
                }
                phase = Phase.RESTORE
            }
            else -> Unit
        }
        return true
    }

    private fun readTopLevel(input: ExtractorInput, holder: PositionHolder, node: Element, body: Long): Boolean? {
        if (node.id == ID_CLUSTER || node.id == ID_CUES) {
            phase = Phase.RESTORE
        } else if (node.id == ID_SEEK_HEAD) {
            val data = peekBody(input, holder, body, node.size) ?: return false
            val entries = seekEntries(data)
            // The main reader already understands direct Cues pointers; avoid extra network seeks.
            if (entries.any { it.first == ID_CUES }) phase = Phase.RESTORE
            else {
                enqueue(entries, input.length)
                nextLink()
            }
        } else {
            cursor = checkedEnd(body, node.size, input.length)
        }
        return null
    }

    private fun readLinked(input: ExtractorInput, holder: PositionHolder, node: Element, body: Long): Boolean? {
        if (node.id != ID_SEEK_HEAD) {
            nextLink()
            return null
        }
        val data = peekBody(input, holder, body, node.size) ?: return false
        val entries = seekEntries(data)
        val cues = entries.firstOrNull { it.first == ID_CUES && validOffset(it.second, input.length) }
        if (cues != null) {
            cursor = segment + cues.second
            phase = Phase.CUES
        } else {
            enqueue(entries, input.length)
            nextLink()
        }
        return null
    }

    private fun enqueue(entries: List<Pair<Int, Long>>, length: Long) {
        entries.filter { it.first == ID_SEEK_HEAD && validOffset(it.second, length) }.forEach {
            val position = segment + it.second
            if (position !in visited && pending.size < MAX_LINKS) pending.add(position)
        }
    }

    private fun nextLink() {
        while (pending.isNotEmpty() && visited.size < MAX_LINKS) {
            val position = pending.removeFirst()
            if (visited.add(position)) {
                cursor = position
                phase = Phase.LINK
                return
            }
        }
        phase = Phase.RESTORE
    }

    private fun validOffset(relative: Long, length: Long) = relative >= 0 && relative < length - segment

    private fun restore(input: ExtractorInput, holder: PositionHolder): Boolean {
        input.resetPeekPosition()
        if (input.position != originalPosition) {
            holder.position = originalPosition
            return false
        }
        phase = Phase.DONE
        return true
    }

    private fun peekBody(input: ExtractorInput, holder: PositionHolder, position: Long, size: Long): ByteArray? {
        require(size in 0..MAX_NODE_BYTES.toLong())
        checkedEnd(position, size, input.length)
        return peek(input, position, size.toInt(), holder)
    }

    private fun peek(input: ExtractorInput, position: Long, size: Int, holder: PositionHolder): ByteArray? {
        require(position in 0 until input.length && size <= input.length - position)
        require(bytesRead <= MAX_BYTES - size)
        val distance = position - input.position
        if (distance < 0 || distance > MAX_PEEK_BYTES - size) {
            holder.position = position
            return null
        }
        input.resetPeekPosition()
        return try {
            input.advancePeekPosition(distance.toInt())
            ByteArray(size).also { input.peekFully(it, 0, size); bytesRead += size }
        } finally {
            input.resetPeekPosition()
        }
    }

    private fun seekEntries(data: ByteArray): List<Pair<Int, Long>> {
        val entries = ArrayList<Pair<Int, Long>>()
        var offset = 0
        while (offset < data.size) {
            val entry = element(data, offset)
            val end = checkedEnd(entry.content.toLong(), entry.size, data.size.toLong()).toInt()
            if (entry.id == ID_SEEK) {
                var id = 0
                var relative = -1L
                var field = entry.content
                while (field < end) {
                    val child = element(data, field)
                    val next = checkedEnd(child.content.toLong(), child.size, end.toLong()).toInt()
                    if (child.id == ID_SEEK_ID) id = unsigned(data, child.content, next).toInt()
                    if (child.id == ID_SEEK_POSITION) relative = unsigned(data, child.content, next)
                    field = next
                }
                if (relative >= 0) entries.add(id to relative)
            }
            offset = end
        }
        return entries
    }

    private fun element(data: ByteArray, offset: Int): Element {
        val (idLength, id) = vint(data, offset, true)
        require(idLength <= 4)
        val (sizeLength, size) = vint(data, offset + idLength, false)
        return Element(id.toInt(), offset + idLength + sizeLength, size)
    }

    private fun vint(data: ByteArray, offset: Int, identifier: Boolean): Pair<Int, Long> {
        require(offset in data.indices && data[offset] != 0.toByte())
        val first = data[offset].toInt() and 0xff
        val length = Integer.numberOfLeadingZeros(first) - 23
        require(length in 1..8 && offset <= data.size - length)
        var value = (if (identifier) first else first and (0xff ushr length)).toLong()
        for (i in 1 until length) value = (value shl 8) or (data[offset + i].toLong() and 0xff)
        return length to value
    }

    private fun unsigned(data: ByteArray, start: Int, end: Int): Long {
        require(end - start in 1..8)
        var value = 0L
        for (i in start until end) value = (value shl 8) or (data[i].toLong() and 0xff)
        require(value >= 0)
        return value
    }

    private fun checkedEnd(start: Long, size: Long, limit: Long): Long {
        require(start in 0..limit && size in 0..limit - start)
        return start + size
    }

    private data class Element(val id: Int, val content: Int, val size: Long)
    private enum class Phase { EBML, SEGMENT, HEADERS, LINK, CUES, RESTORE, DONE }

    private companion object {
        const val ID_EBML = 0x1A45DFA3
        const val ID_SEGMENT = 0x18538067
        const val ID_SEEK_HEAD = 0x114D9B74
        const val ID_SEEK = 0x4DBB
        const val ID_SEEK_ID = 0x53AB
        const val ID_SEEK_POSITION = 0x53AC
        const val ID_CUES = 0x1C53BB6B
        const val ID_CLUSTER = 0x1F43B675
        const val HEADER_BYTES = 12
        const val MAX_LINKS = 8
        const val MAX_HEADERS = 64
        const val MAX_NODE_BYTES = 32 * 1024
        const val MAX_PEEK_BYTES = 64 * 1024
        const val MAX_BYTES = 256 * 1024
    }
}

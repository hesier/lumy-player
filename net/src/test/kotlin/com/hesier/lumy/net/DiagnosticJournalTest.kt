package com.hesier.lumy.net

import com.hesier.lumy.net.DiagnosticJournal.Event
import com.hesier.lumy.net.DiagnosticJournal.Label
import com.hesier.lumy.net.DiagnosticJournal.Metric
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagnosticJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun journal() = DiagnosticJournal(File(temporary.newFolder(), "diagnostics.json"))

    @Test fun `only technical allowlisted labels survive`() {
        val journal = journal()
        val secrets = listOf("https://host/private?token=secret", "/movies/private.mkv", "My private title", "user@example.com", "secret-token", "c2.vendor/secret", "OMX.vendor\nsecret")
        for (secret in secrets) {
            journal.record(Event.PLAN, labels = Label.entries.associateWith { secret })
        }
        val exported = journal.snapshot()
        secrets.forEach { assertFalse(exported.contains(it)) }
        assertTrue(exported.contains("unknown"))
        assertFalse(exported.contains("title"))
        assertFalse(exported.contains("url"))
    }

    @Test fun `records preserve technical fields and numeric errors`() {
        val journal = journal()
        journal.record(Event.ERROR, mapOf(Metric.ERROR_CODE to 4001, Metric.POSITION_MS to 1234))
        journal.record(Event.VIDEO_DECODER, labels = mapOf(Label.DECODER to "c2.qti.hevc.decoder"))
        journal.record(Event.PLAN, mapOf(Metric.WIDTH to 3840, Metric.REASON_MASK to 3), mapOf(Label.VIDEO_CODEC to "hevc", Label.MODE to "transcode"))
        val records = Json.parseToJsonElement(journal.snapshot()).jsonArray
        assertEquals(3, records.size)
        assertEquals("4001", records[0].jsonObject.getValue("metrics").jsonObject.getValue("ERROR_CODE").toString())
        assertTrue(journal.snapshot().contains("c2.qti.hevc.decoder"))
        assertTrue(journal.snapshot().contains("transcode"))
    }

    @Test fun `bounded history survives restart after leaving playback`() {
        val file = File(temporary.newFolder(), "diagnostics.json")
        val journal = DiagnosticJournal(file)
        repeat(DiagnosticJournal.MAX_RECORDS * 3) {
            journal.record(Event.STATE, mapOf(Metric.POSITION_MS to it.toLong()))
        }
        journal.record(Event.STOP, mapOf(Metric.POSITION_MS to 999))
        val expected = journal.snapshot()
        assertEquals(DiagnosticJournal.MAX_RECORDS, Json.parseToJsonElement(expected).jsonArray.size)
        val deadline = System.nanoTime() + 3_000_000_000L
        while ((!file.exists() || file.readText() != expected) && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(expected, file.readText())
        assertTrue(file.length() <= DiagnosticJournal.MAX_BYTES)
        assertEquals(expected, DiagnosticJournal(file).snapshot())
    }

    @Test fun `maximum record schema remains within byte budget`() {
        val journal = journal()
        repeat(DiagnosticJournal.MAX_RECORDS + 1) {
            journal.record(Event.PLAN, Metric.entries.associateWith { Long.MAX_VALUE },
                Label.entries.associateWith { if (it == Label.DECODER) "OMX." + "x".repeat(116) else "unknown" })
        }
        assertTrue(journal.snapshot().toByteArray().size <= DiagnosticJournal.MAX_BYTES)
    }

    @Test fun `invalid previous file never enters export`() {
        val file = temporary.newFile()
        file.writeText("private URL and token")
        assertEquals("[]", DiagnosticJournal(file).snapshot())
    }
}

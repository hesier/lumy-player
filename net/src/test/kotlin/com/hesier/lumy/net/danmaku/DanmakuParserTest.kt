package com.hesier.lumy.net.danmaku

import org.junit.Assert.*
import org.junit.Test

class DanmakuParserTest {
    private fun parse(text: String, format: DanmakuFormat = DanmakuFormat.JSON) =
        DanmakuParser.parse(text.toByteArray(), format)

    @Test fun dandanUsesFourFieldsAndAcceptsLongIds() {
        val comments = parse("""{"success":true,"comments":[
            {"cid":9223372036854775806,"p":"1.250,1,16711680,sender","m":"hello"},
            {"cid":"opaque","p":"0,4,-1,user","m":"bottom"}]}""")
        assertEquals(2, comments.size)
        assertEquals(0L, comments[0].timeMs)
        assertEquals(0xFFFFFF, comments[0].color)
        assertNotEquals(comments[0].id, comments[1].id)
        assertEquals(1_250L, comments[1].timeMs)
        assertEquals(0xFF0000, comments[1].color)
    }

    @Test fun biliXmlReadsColorFromFourthFieldAndUnescapesText() {
        val comments = parse("""<?xml version="1.0"?><i><chatid>1</chatid>
            <d p="2,5,25,255,0,0,user,9007199254740993">A &amp; B</d></i>""", DanmakuFormat.XML)
        assertEquals(1, comments.size)
        assertEquals(2_000L, comments[0].timeMs)
        assertEquals("A & B", comments[0].text)
        assertEquals(5, comments[0].mode)
        assertEquals(255, comments[0].color)
    }

    @Test fun embyGroupsIgnoreOpenedAndDeduplicateSemanticContent() {
        val comments = parse("""{"hasNext":false,"data":[
            {"source":"a","opened":false,"danmuEvents":[{"p":"2,1,25,255,0,0,u,10","m":"x"}]},
            {"source":"b","danmuEvents":[{"p":"2,1,25,255,0,0,v,20","m":"x"},
            {"p":"3,4,25,16777215,0,0,v,21","m":"y"}]}]}""", DanmakuFormat.EMBY_JSON)
        assertEquals(2, comments.size)
        assertEquals("x", comments[0].text)
        assertNotEquals(comments[0].id, comments[1].id)
    }

    @Test fun reusedRemoteIdsHaveDistinctStableInternalIds() {
        val json = """{"comments":[{"cid":1,"p":"0,1,255,u","m":"first"},
            {"cid":1,"p":"0,1,255,u","m":"second"},{"cid":"opaque","p":"1,1,255,u","m":"third"}]}"""
        val first = parse(json)
        assertEquals(3, first.map { it.id }.toSet().size)
        assertEquals(first, parse(json))
        val groups = """{"data":[{"danmuEvents":[{"p":"0,1,25,255,0,0,u,1","m":"first"}]},
            {"danmuEvents":[{"p":"0,1,25,255,0,0,u,1","m":"second"}]}]}"""
        assertEquals(2, parse(groups, DanmakuFormat.EMBY_JSON).map { it.id }.toSet().size)
    }

    @Test fun emptyPayloadsAreDifferentFromInvalidRecords() {
        assertTrue(parse("""{"comments":[]}""").isEmpty())
        assertTrue(parse("[]").isEmpty())
        assertTrue(parse("<i/>", DanmakuFormat.XML).isEmpty())
        assertTrue(parse("""{"data":null}""", DanmakuFormat.EMBY_JSON).isEmpty())
        assertTrue(parse("""{"data":[]}""", DanmakuFormat.EMBY_JSON).isEmpty())
        listOf("{}", "", "<html/>", """{"comments":[{"p":"bad","m":"x"}]}""").forEach {
            assertFailure(DanmakuFailure.INVALID_RESPONSE) { parse(it) }
        }
        assertFailure(DanmakuFailure.UNAVAILABLE) { parse("""{"success":false,"comments":[]}""") }
    }

    @Test fun skipsMalformedRecordsAndTruncatesAtCodepointBoundary() {
        val text = "🙂".repeat(90)
        val comments = parse("""{"comments":[
            {"p":"NaN,1,1,u","m":"x"},{"p":"-1,1,1,u","m":"x"},
            {"p":"86401,1,1,u","m":"x"},{"p":"0,7,1,u","m":"x"},
            {"p":"86400,1,4294967295,u","m":"$text"}]}""")
        assertEquals(1, comments.size)
        assertEquals(86_400_000L, comments[0].timeMs)
        assertEquals(80, comments[0].text.codePointCount(0, comments[0].text.length))
        assertEquals(0xFFFFFF, comments[0].color)
    }

    @Test fun refusesDtdAndExcessiveRecords() {
        assertFailure(DanmakuFailure.INVALID_RESPONSE) {
            parse("""<!DOCTYPE i [<!ENTITY x SYSTEM "file:///etc/passwd">]><i><d p="0,1,25,1,0,0,u,1">&x;</d></i>""",
                DanmakuFormat.XML)
        }
        val row = """{"p":"0,1,1,u","m":"x"}"""
        assertFailure(DanmakuFailure.TOO_LARGE) { parse("[" + List(100_001) { row }.joinToString(",") + "]") }
        assertFailure(DanmakuFailure.TOO_LARGE) {
            DanmakuParser.parse(ByteArray(MAX_DANMAKU_BYTES + 1), DanmakuFormat.JSON)
        }
    }
}

internal fun assertFailure(expected: DanmakuFailure, block: () -> Unit) {
    val failure = runCatching(block).exceptionOrNull()
    assertTrue("Expected DanmakuException, got ${failure?.javaClass?.simpleName}", failure is DanmakuException)
    assertEquals(expected, (failure as DanmakuException).reason)
}

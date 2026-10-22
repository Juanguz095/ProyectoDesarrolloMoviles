package com.auditlab.domain

import java.util.UUID
import kotlin.test.*

class CsvReportTest {
    @Test fun `empty snapshot has only fixed header and CRLF`() {
        assertEquals("eventId,timestampEpochMs,kind,direction,isSimulated,consentId,policyVersion\r\n", CsvReport.render(emptyList()))
    }

    @Test fun `one row uses exact numeric timestamp enums and simulation mark`() {
        val consent = ConsentContext(UUID(0, 1), "1.0", 1000)
        val item = event(UUID(0, 2), 1700000000123, consent, EventKind.MESSAGE, EventDirection.OUTGOING)
        assertEquals("${CsvReport.HEADER}\r\n${item.eventId},1700000000123,MESSAGE,OUTGOING,true,${consent.consentId},1.0\r\n", CsvReport.render(listOf(item)))
    }

    @Test fun `all four event combinations serialize`() {
        val items = EventKind.entries.flatMap { kind -> EventDirection.entries.map { event(kind = kind, direction = it) } }
        val report = CsvReport.render(items)
        for (kind in EventKind.entries) for (direction in EventDirection.entries) assertContains(report, ",$kind,$direction,true,")
        assertEquals(6, report.split("\r\n").size)
    }

    @Test fun `rows have deterministic descending timestamp and ascending UUID tie order`() {
        val items = mutableListOf(event(UUID(0, 3), 2000), event(UUID(0, 2), 3000), event(UUID(0, 1), 3000))
        val before = items.toList()
        val ids = CsvReport.render(items).split("\r\n").drop(1).filter { it.isNotEmpty() }.map { it.substringBefore(',') }
        assertEquals(listOf(UUID(0, 1), UUID(0, 2), UUID(0, 3)).map { it.toString() }, ids)
        assertEquals(before, items)
    }

    @Test fun `thousand row snapshot is not silently truncated to visible hundred`() {
        val items = List(1000) { event(UUID(0, it.toLong()), 2000L + it) }
        val rows = CsvReport.render(items).split("\r\n").drop(1).filter { it.isNotEmpty() }
        assertEquals(1000, rows.size)
        assertEquals(items.map { it.eventId.toString() }.toSet(), rows.map { it.substringBefore(',') }.toSet())
    }

    @Test fun `plain empty and unicode synthetic cells are preserved`() {
        for (value in listOf("", "texto", " SIMULADO ", "áéíóú ñ 漢字 😀", "123")) assertEquals(value, CsvReport.encodeCell(value))
    }

    @Test fun `comma quotes and embedded newlines are escaped`() {
        assertEquals("\"a,b\"", CsvReport.encodeCell("a,b"))
        assertEquals("\"a\"\"b\"", CsvReport.encodeCell("a\"b"))
        for (newline in listOf("\n", "\r", "\r\n")) assertEquals("\"a${newline}b\"", CsvReport.encodeCell("a${newline}b"))
    }

    @Test fun `formula prefixes are neutralized even behind whitespace controls and BOM`() {
        for (prefix in listOf("", " ", "\t", "\u0000", "\uFEFF", "\u200B", " \t ")) {
            for (formula in listOf("=1+1", "+SUM(A1)", "-1", "@SUM(A1)")) {
                val raw = prefix + formula
                assertEquals("'$raw", CsvReport.encodeCell(raw))
            }
        }
    }

    @Test fun `formula protection is applied before quoting`() {
        assertEquals("\"'=SUM(1,2)\"", CsvReport.encodeCell("=SUM(1,2)"))
        assertEquals("\"'\n=1\"", CsvReport.encodeCell("\n=1"))
        assertEquals("\"'\r@x\"", CsvReport.encodeCell("\r@x"))
        assertEquals("'\ttext", CsvReport.encodeCell("\ttext"))
        assertEquals("\"'=\"\"a\"\"\"", CsvReport.encodeCell("=\"a\""))
    }

    @Test fun `numeric zero and maximum timestamp have no locale or scientific formatting`() {
        for (time in listOf(0L, Long.MAX_VALUE)) {
            val item = event(time = time, consent = context(time = 0))
            assertEquals(time.toString(), CsvReport.render(listOf(item)).split("\r\n")[1].split(',')[1])
        }
    }
}

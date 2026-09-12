package com.abhinavxt.newsforge.core.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolTableTest {

    @Test
    fun parsesSymbolNameAndAliases() {
        val entries = SymbolTable.parse(
            "RELIANCE\tReliance Industries\tRIL|Jio Platforms\n" +
                "TCS\tTata Consultancy Services\n"
        )
        assertEquals(2, entries.size)
        assertEquals("RELIANCE", entries[0].symbol)
        assertEquals("Reliance Industries", entries[0].name)
        assertEquals(listOf("RIL", "Jio Platforms"), entries[0].aliases)
        assertTrue(entries[1].aliases.isEmpty())
    }

    @Test
    fun ignoresCommentsAndBlankLines() {
        val entries = SymbolTable.parse(
            "# generated from instruments.csv\n" +
                "\n" +
                "   \n" +
                "INFY\tInfosys\n"
        )
        assertEquals(1, entries.size)
        assertEquals("INFY", entries.single().symbol)
    }

    @Test
    fun firstRowWinsForADuplicateSymbol() {
        // A generator that merged two exchanges' rows would emit the symbol twice.
        val entries = SymbolTable.parse("ACME\tFirst\nACME\tSecond\n")
        assertEquals(1, entries.size)
        assertEquals("First", entries.single().name)
    }

    @Test
    fun toleratesMissingNameAndRaggedRows() {
        val entries = SymbolTable.parse("ACME\n" + "OTHER\t\tX|Y\n")
        assertEquals(listOf("ACME", "OTHER"), entries.map { it.symbol })
        // A missing name falls back to the symbol rather than dropping the entry.
        assertEquals("ACME", entries[0].name)
        assertEquals("OTHER", entries[1].name)
        assertEquals(listOf("X", "Y"), entries[1].aliases)
    }

    @Test
    fun skipsRowsWithNoSymbol() {
        assertTrue(SymbolTable.parse("\tNo symbol here\n").isEmpty())
    }

    @Test
    fun roundTripsThroughFormat() {
        val original = listOf(
            SymbolEntry("RELIANCE", "Reliance Industries", listOf("RIL")),
            SymbolEntry("TCS", "Tata Consultancy Services"),
        )
        assertEquals(original, SymbolTable.parse(SymbolTable.format(original)))
    }

    @Test
    fun parsedEntriesFeedTheLexicon() {
        val lexicon = SymbolLexicon(
            SymbolTable.parse("HAVELLS\tHavells India\tHavells\nPIDILITIND\tPidilite Industries\n")
        )
        assertEquals(listOf("HAVELLS"), lexicon.match("Havells India posts Q2 numbers"))
        assertEquals(listOf("PIDILITIND"), lexicon.match("Pidilite Industries raises prices"))
    }
}

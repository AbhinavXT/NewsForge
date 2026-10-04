package com.abhinavxt.newsforge.core.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentCsvTest {

    private val header =
        "SYMBOL,NAME OF COMPANY, SERIES, DATE OF LISTING, PAID UP VALUE, MARKET LOT, " +
            "ISIN NUMBER, FACE VALUE"

    private fun csv(vararg rows: String) = (listOf(header) + rows).joinToString("\n")

    @Test
    fun readsSymbolAndCompanyName() {
        val entries = InstrumentCsv.parse(
            csv("BEL,Bharat Electronics Limited,EQ,01-JAN-1995,1,1,INE263A01024,1")
        )
        val entry = entries.single()
        assertEquals("BEL", entry.symbol)
        assertEquals("Bharat Electronics Limited", entry.name)
        assertTrue(entry.generated)
    }

    @Test
    fun theLegalSuffixIsDroppedIntoAnAlias() {
        // Nobody writes "Bharat Electronics Limited" in a headline, so the phrase table
        // needs the form people actually use — and the full name kept as well.
        val entry = InstrumentCsv.parse(
            csv("BEL,Bharat Electronics Limited,EQ,01-JAN-1995,1,1,INE263A01024,1")
        ).single()
        assertEquals(listOf("Bharat Electronics"), entry.aliases)
    }

    @Test
    fun namesWithoutASuffixGetNoRedundantAlias() {
        val entry = InstrumentCsv.parse(
            csv("63MOONS,63 moons technologies,EQ,20-JUN-2005,2,1,INE111B01023,2"
        )).single()
        assertTrue(entry.aliases.isEmpty())
    }

    @Test
    fun indiaIsNotTreatedAsALegalSuffix() {
        // Dropping it turns Coal India into Coal, which is a blocked word that would then
        // match nothing at all.
        val entry = InstrumentCsv.parse(
            csv("COALINDIA,Coal India Limited,EQ,04-NOV-2010,10,1,INE522F01014,10")
        ).single()
        assertEquals(listOf("Coal India"), entry.aliases)
    }

    @Test
    fun onlyTradeableEquitySeriesAreKept() {
        val entries = InstrumentCsv.parse(
            csv(
                "AAA,A Company Limited,EQ,01-JAN-2020,1,1,INE000A01001,1",
                "BBB,B Company Limited,BE,01-JAN-2020,1,1,INE000A01002,1",
                "CCC,C Bond Series,N1,01-JAN-2020,1,1,INE000A01003,1",
                "DDD,D Warrant,W3,01-JAN-2020,1,1,INE000A01004,1",
            )
        )
        assertEquals(listOf("AAA", "BBB"), entries.map { it.symbol })
    }

    @Test
    fun aQuotedCommaDoesNotShiftTheColumns() {
        // A plain split would read this company's series out of its own name and drop the
        // row, silently and without a count changing enough to notice.
        val entry = InstrumentCsv.parse(
            csv("""XYZ,"Acme, Bros and Company Limited",EQ,01-JAN-2020,1,1,INE000A01005,1""")
        ).single()
        assertEquals("Acme, Bros and Company Limited", entry.name)
        assertEquals("XYZ", entry.symbol)
    }

    @Test
    fun theHeaderAndBlankLinesAreNotCompanies() {
        assertTrue(InstrumentCsv.parse(csv("", "   ")).isEmpty())
    }

    @Test
    fun aRepeatedSymbolIsKeptOnce() {
        val entries = InstrumentCsv.parse(
            csv(
                "AAA,First Listing Limited,EQ,01-JAN-2020,1,1,INE000A01001,1",
                "AAA,Second Listing Limited,EQ,01-JAN-2021,1,1,INE000A01009,1",
            )
        )
        assertEquals(1, entries.size)
        assertEquals("First Listing Limited", entries.single().name)
    }

    @Test
    fun aBulkEntryCannotClaimAShortCommonWord() {
        // The reason `generated` exists. Two hundred curated names can be checked by hand;
        // two thousand imported ones cannot, and Trent is a river before it is a retailer.
        val lexicon = SymbolLexicon(
            InstrumentCsv.parse(
                csv(
                    "TRENT,Trent Limited,EQ,01-JAN-1995,1,1,INE849A01020,1",
                    "SYMBIOTEC,Symbiotec Pharmalab Limited,EQ,01-JAN-2020,1,1,INE000A01007,1",
                )
            )
        )
        assertFalse("TRENT" in lexicon.match("the trent flooded again last night"))
        assertTrue("SYMBIOTEC" in lexicon.match("symbiotec said the plant is running"))
        // The full name still works for the blocked one, which is the point of the trade.
        assertTrue("TRENT" in lexicon.match("Trent Limited reported higher revenue"))
    }

    @Test
    fun aHandWrittenEntryKeepsItsSingleTokenClaim() {
        val lexicon = SymbolLexicon(
            listOf(SymbolEntry(symbol = "TITAN", name = "Titan Company Limited"))
        )
        assertTrue("TITAN" in lexicon.match("titan gained after the update"))
    }
}

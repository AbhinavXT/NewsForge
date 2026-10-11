package com.abhinavxt.newsforge.core.desk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The board's wire format, which TickerForge's `board.as_payload` writes and this reads.
 * The row below is the one `test_board_payload_shape` asserts on the desk side, so the two
 * test suites pin the same bytes.
 */
class BoardsTest {

    private fun payload(
        kind: String = "board",
        bull: String = "1|BULL0|90|1234.5|2.1|LB|1221|1230.25|-0.4|1||1.8|27.3|2.2;" +
            "2|BULL1|89|1235.5|2.1|LB|1221|1230.25|-0.4||+PDL|1.8|27.3|2.2",
        bear: String = "1|BEAR0|88|900|-1.7|SB|905|910|0.6||-EQH|||",
    ): Map<String, String?> = mapOf(
        DeskPayloads.VERSION_KEY to "1",
        "kind" to kind,
        "ts" to "1791780720",
        "live" to "1",
        "stale" to "0",
        "breadth" to "40|120|25",
        "sectors" to "Metals|1.23|7;IT|-0.5|9",
        "bull" to bull,
        "bear" to bear,
    )

    @Test
    fun `reads breadth sectors and both sides`() {
        val board = Boards.fromFlat(payload())!!
        assertEquals(1_791_780_720_000L, board.takenAtMillis)
        assertTrue(board.live)
        assertFalse(board.stale)
        assertEquals(185, board.total)
        assertEquals(listOf("Metals", "IT"), board.sectors.map { it.name })
        assertEquals(-0.5, board.sectors[1].changePercent, 1e-9)
        assertEquals(listOf("BULL0", "BULL1"), board.bull.map { it.symbol })
        assertEquals(listOf("BEAR0"), board.bear.map { it.symbol })
    }

    @Test
    fun `reads every positional field of a row`() {
        val row = Boards.fromFlat(payload())!!.bull.first()
        assertEquals(BoardSide.BULL, row.side)
        assertEquals(1, row.rank)
        assertEquals(90, row.score)
        assertEquals(1234.5, row.ltp!!, 1e-9)
        assertEquals(2.1, row.changePercent!!, 1e-9)
        assertEquals(OiRead.LONG_BUILDUP, row.oiRead)
        assertEquals(1221.0, row.orderBlockBottom!!, 1e-9)
        assertEquals(1230.25, row.orderBlockTop!!, 1e-9)
        assertEquals(-0.4, row.orderBlockDistancePercent!!, 1e-9)
        assertTrue(row.reversal)
        assertNull(row.trap)
        assertEquals(27.3, row.adx!!, 1e-9)
        assertEquals(5, row.conviction)
    }

    @Test
    fun `trap carries its direction and level`() {
        val board = Boards.fromFlat(payload())!!
        val bull = board.bull[1].trap!!
        assertTrue(bull.bullish)
        assertEquals("PDL", bull.label)
        val bear = board.bear[0].trap!!
        assertFalse(bear.bullish)
        assertEquals("EQH", bear.label)
    }

    @Test
    fun `empty fields are unknown not zero`() {
        val row = Boards.fromFlat(payload())!!.bear.first()
        assertNull(row.volumeRatio)
        assertNull(row.adx)
        assertNull(row.moveAtr)
    }

    @Test
    fun `short and blank rows are skipped without shifting the rest`() {
        val board = Boards.fromFlat(payload(bull = ";1|X;2|GOOD|50|10|1"))!!
        assertEquals(listOf("GOOD"), board.bull.map { it.symbol })
    }

    @Test
    fun `other kinds are not boards`() {
        assertNull(Boards.fromFlat(payload(kind = "screen")))
    }

    @Test
    fun `a board is machine data, never a quote`() {
        val flat = payload()
        assertTrue(DeskPayloads.isMachine(flat))
        assertNull(DeskPayloads.fromFlat(flat))
    }

    @Test
    fun `the app's own request is recognised`() {
        assertTrue(Boards.isRequest(" /board nf "))
        assertFalse(Boards.isRequest("/board"))
    }
}

package com.abhinavxt.newsforge.core.notify

import com.abhinavxt.newsforge.core.desk.BoardRow
import com.abhinavxt.newsforge.core.desk.BoardSide
import com.abhinavxt.newsforge.core.desk.BoardSnapshot
import com.abhinavxt.newsforge.core.desk.BoardTrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardAlertsTest {

    private val now = 1_791_780_720_000L

    private fun row(
        symbol: String,
        rank: Int = 1,
        side: BoardSide = BoardSide.BULL,
        reversal: Boolean = false,
        trap: BoardTrap? = null,
    ) = BoardRow(side, rank, symbol, 80, 100.0, 1.0, null, 99.0, 99.5, -0.3, reversal, trap, null, null, null)

    private fun board(
        vararg rows: BoardRow,
        live: Boolean = true,
        stale: Boolean = false,
        takenAt: Long = now - 60_000,
    ) = BoardSnapshot(
        takenAtMillis = takenAt, live = live, stale = stale,
        bullCount = 1, choppyCount = 0, bearCount = 0,
        bull = rows.filter { it.side == BoardSide.BULL },
        bear = rows.filter { it.side == BoardSide.BEAR },
        sectors = emptyList(),
    )

    private fun select(
        board: BoardSnapshot,
        followed: Set<String> = setOf("SBIN"),
        notified: Set<String> = emptySet(),
        quiet: Boolean = false,
    ) = BoardAlerts.select(board, followed, notified, now, quiet, enabled = true)

    @Test
    fun `rings for a trap and a reversal on a followed name`() {
        val alerts = select(board(row("SBIN", reversal = true, trap = BoardTrap(true, "PDL"))))
        assertEquals(listOf(BoardAlerts.Kind.TRAP, BoardAlerts.Kind.REVERSAL), alerts.map { it.kind })
        assertEquals("SBIN · liquidity trap ▲ PDL", alerts[0].title)
    }

    @Test
    fun `ignores names not followed and rows with no flag`() {
        val alerts = select(board(row("INFY", trap = BoardTrap(false, "EQH")), row("SBIN", rank = 2)))
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `never rings for a closing or cached board`() {
        val flagged = row("SBIN", reversal = true)
        assertTrue(select(board(flagged, live = false)).isEmpty())
        assertTrue(select(board(flagged, stale = true)).isEmpty())
        assertTrue(select(board(flagged, takenAt = now - BoardAlerts.MAX_AGE_MS - 1)).isEmpty())
    }

    @Test
    fun `once per name flag and day`() {
        val flagged = board(row("SBIN", reversal = true))
        val first = select(flagged)
        assertEquals(1, first.size)
        assertTrue(select(flagged, notified = setOf(first[0].key)).isEmpty())
    }

    @Test
    fun `respects quiet hours and the ceiling`() {
        val many = (1..6).map { row("S$it", rank = it, reversal = true) }.toTypedArray()
        assertTrue(select(board(*many), followed = many.map { it.symbol }.toSet(), quiet = true).isEmpty())
        assertEquals(
            BoardAlerts.MAX_PER_BOARD,
            select(board(*many), followed = many.map { it.symbol }.toSet()).size,
        )
    }
}

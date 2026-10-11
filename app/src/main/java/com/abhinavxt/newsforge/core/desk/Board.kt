package com.abhinavxt.newsforge.core.desk

import java.util.Locale

/**
 * The desk's F&O order-block board: every F&O stock scanned for displacement and the order
 * block that launched it, ranked strongest first on each side.
 *
 * TickerForge does the scan — it holds the futures open interest and the intraday bars for
 * two hundred names, which a phone has no business fetching. What it sends is the result,
 * and this is the shape of it. The point of having it here rather than only on the desk is
 * the column next to it: the board says which names are moving, the news says why.
 *
 * ## Wire format
 *
 * ```json
 * {"nf":1,"kind":"board","ts":1789600000,"live":1,"stale":0,
 *  "breadth":"40|120|25",
 *  "sectors":"Metals|1.23|7;IT|-0.5|9",
 *  "bull":"1|SBIN|82|842.35|2.84|LB|836.2|838.4|-0.5|1|+PDL|1.8|27.3|2.2;2|...",
 *  "bear":"..."}
 * ```
 *
 * Rows travel as strings for the reason [Candles] gives: the flattener skips arrays, and
 * keyed objects would not fit the message limit anyway. Fields within a row are
 * positional — rank, symbol, score, last, change %, OI read, order-block bottom and top,
 * distance to the block in %, reversal (1 or empty), trap (direction sign and level kind),
 * volume multiple, ADX, displacement size in ATRs. An empty field is unknown, never zero.
 */
data class BoardSnapshot(
    val takenAtMillis: Long?,
    /** Built during the session; false is the last session's closing board. */
    val live: Boolean,
    /** The desk served a cached board rather than building one; say so. */
    val stale: Boolean,
    val bullCount: Int,
    val choppyCount: Int,
    val bearCount: Int,
    val bull: List<BoardRow>,
    val bear: List<BoardRow>,
    /** Average move per sector, strongest first. */
    val sectors: List<SectorFlow>,
) {
    val total: Int get() = bullCount + choppyCount + bearCount

    fun rows(side: BoardSide): List<BoardRow> = if (side == BoardSide.BULL) bull else bear

    /** Every ranked name, both sides. */
    val symbols: List<String> get() = (bull + bear).map { it.symbol }
}

enum class BoardSide(val label: String) {
    BULL("Bullish"),
    BEAR("Bearish"),
}

data class BoardRow(
    val side: BoardSide,
    val rank: Int,
    val symbol: String,
    /** 0–100: displacement, OI, volume, trend strength and proximity to the block. */
    val score: Int,
    val ltp: Double?,
    val changePercent: Double?,
    /** Price × futures OI: [OiRead.LONG_BUILDUP] and friends. */
    val oiRead: OiRead?,
    val orderBlockBottom: Double?,
    val orderBlockTop: Double?,
    /** Signed % from the last price to the nearest edge of the block; 0 inside it. */
    val orderBlockDistancePercent: Double?,
    /** Price is back at its live block and OI or a sweep says the side is defending it. */
    val reversal: Boolean,
    val trap: BoardTrap?,
    val volumeRatio: Double?,
    val adx: Double?,
    val moveAtr: Double?,
) {
    val hasOrderBlock: Boolean get() = orderBlockBottom != null && orderBlockTop != null

    /** Inside the zone, where the trade is. */
    val inZone: Boolean get() = orderBlockDistancePercent == 0.0

    /** Score as the five-dot conviction meter: 20 points a dot, rounded. */
    val conviction: Int get() = ((score + 10) / 20).coerceIn(0, 5)
}

/**
 * A liquidity sweep: a bar ran a resting level and closed back inside it.
 *
 * Above a high it traps breakout buyers and points down; below a low it traps breakdown
 * sellers and points up — [bullish] is the direction it points, not the level's side.
 */
data class BoardTrap(val bullish: Boolean, val level: String) {
    /** "PDL", "EQH" and so on, as the desk names them. */
    val label: String get() = level.uppercase(Locale.US)
}

enum class OiRead(val code: String, val label: String) {
    LONG_BUILDUP("LB", "Long build-up"),
    SHORT_BUILDUP("SB", "Short build-up"),
    SHORT_COVERING("SC", "Short covering"),
    LONG_UNWINDING("LU", "Long unwinding"),
    ;

    companion object {
        fun parse(code: String?): OiRead? =
            entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) }
    }
}

data class SectorFlow(val name: String, val changePercent: Double, val count: Int?)

object Boards {

    const val KIND: String = "board"

    /**
     * What the app sends to ask for one: TickerForge's `/board nf`, which pushes the board
     * as this payload instead of replying in text.
     */
    const val COMMAND: String = "/board nf"

    /** The request's own echo on a shared topic, which the desk log should not show. */
    fun isRequest(body: String): Boolean = body.trim().equals(COMMAND, ignoreCase = true)

    /** Rows per side kept. The desk sends twenty; anything beyond is a sender bug. */
    private const val MAX_ROWS = 50
    private const val MIN_FIELDS = 5

    fun fromFlat(record: Map<String, String?>): BoardSnapshot? {
        if (record[DeskPayloads.VERSION_KEY]?.trim()?.toIntOrNull() != DeskPayloads.SUPPORTED_VERSION) {
            return null
        }
        if (record["kind"]?.trim()?.lowercase(Locale.US) != KIND) return null

        val breadth = record["breadth"].orEmpty().split('|').map { it.trim().toIntOrNull() ?: 0 }
        return BoardSnapshot(
            takenAtMillis = record["ts"]?.trim()?.toLongOrNull()?.times(1000),
            live = record["live"]?.trim() == "1",
            stale = record["stale"]?.trim() == "1",
            bullCount = breadth.getOrElse(0) { 0 },
            choppyCount = breadth.getOrElse(1) { 0 },
            bearCount = breadth.getOrElse(2) { 0 },
            bull = rows(record["bull"], BoardSide.BULL),
            bear = rows(record["bear"], BoardSide.BEAR),
            sectors = sectors(record["sectors"]),
        )
    }

    private fun rows(raw: String?, side: BoardSide): List<BoardRow> {
        val out = ArrayList<BoardRow>()
        for (row in raw.orEmpty().splitToSequence(';')) {
            if (out.size >= MAX_ROWS) break
            val f = row.split('|')
            if (f.size < MIN_FIELDS) continue
            val symbol = f[1].trim().uppercase(Locale.US).takeIf { it.isNotEmpty() } ?: continue
            out += BoardRow(
                side = side,
                rank = f[0].trim().toIntOrNull() ?: (out.size + 1),
                symbol = symbol,
                score = f[2].trim().toIntOrNull()?.coerceIn(0, 100) ?: 0,
                ltp = f.num(3),
                changePercent = f.num(4),
                oiRead = OiRead.parse(f.getOrNull(5)),
                orderBlockBottom = f.num(6),
                orderBlockTop = f.num(7),
                orderBlockDistancePercent = f.num(8),
                reversal = f.getOrNull(9)?.trim() == "1",
                trap = trap(f.getOrNull(10)),
                volumeRatio = f.num(11),
                adx = f.num(12),
                moveAtr = f.num(13),
            )
        }
        return out
    }

    private fun trap(raw: String?): BoardTrap? {
        val text = raw?.trim().orEmpty()
        if (text.length < 2) return null
        val bullish = when (text[0]) {
            '+' -> true
            '-' -> false
            else -> return null
        }
        return BoardTrap(bullish, text.substring(1))
    }

    private fun sectors(raw: String?): List<SectorFlow> =
        raw.orEmpty().splitToSequence(';').mapNotNull { row ->
            val f = row.split('|')
            val name = f.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val change = f.num(1) ?: return@mapNotNull null
            SectorFlow(name, change, f.getOrNull(2)?.trim()?.toIntOrNull())
        }.toList()

    private fun List<String>.num(i: Int): Double? =
        getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }
}

package com.abhinavxt.newsforge.core.tag

import java.util.Locale

/**
 * The exchange's own list of what is listed, as tagging input.
 *
 * Tagging decides whether a story reaches you at all — a company the lexicon has never
 * heard of produces an article with no symbols, which no watchlist filter will surface and
 * no alert will fire for. That failure is silent: nothing in the app can tell you about
 * the story it did not tag. So recall here is worth more than almost anything else, and a
 * hand-maintained list of a few hundred names is a permanent ceiling on it.
 *
 * NSE publishes the whole equity list as a CSV with no authentication. Read in the app
 * rather than bundled at build time, so a company listed last month is taggable this
 * afternoon instead of at the next release.
 *
 * Columns, in order: SYMBOL, NAME OF COMPANY, SERIES, DATE OF LISTING, PAID UP VALUE,
 * MARKET LOT, ISIN NUMBER, FACE VALUE.
 */
object InstrumentCsv {

    const val URL: String = "https://nsearchives.nseindia.com/content/equities/EQUITY_L.csv"

    /**
     * Series worth tagging.
     *
     * EQ is normal trading and BE is trade-to-trade — restricted, but news about those
     * companies is often exactly what you want to see, since the restriction tends to
     * arrive with a story attached. Everything else on the exchange is not a company.
     */
    private val SERIES = setOf("EQ", "BE")

    /**
     * Trailing words that are legal form rather than name.
     *
     * Only ever stripped from the end, and only these. "India" is not here on purpose:
     * dropping it turns Coal India into Coal and Bank of India into Bank of, and both of
     * those are already blocked words that would then match nothing at all.
     */
    private val SUFFIXES = listOf("limited", "ltd.", "ltd")

    fun parse(text: String): List<SymbolEntry> {
        val entries = ArrayList<SymbolEntry>()
        val seen = HashSet<String>()

        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r', '\n')
            if (line.isBlank()) continue

            val columns = splitRow(line)
            val symbol = columns.getOrNull(0)?.trim()?.uppercase(Locale.US).orEmpty()
            if (symbol.isEmpty() || symbol == "SYMBOL") continue
            val series = columns.getOrNull(2)?.trim()?.uppercase(Locale.US).orEmpty()
            if (series !in SERIES) continue
            if (!seen.add(symbol)) continue

            val name = columns.getOrNull(1)?.trim().orEmpty().ifEmpty { symbol }
            entries += SymbolEntry(
                symbol = symbol,
                name = name,
                aliases = listOfNotNull(shortName(name).takeIf { it != name }),
                generated = true,
            )
        }
        return entries
    }

    /**
     * The name without its legal suffix.
     *
     * "Bharat Electronics Limited" is never written that way in a headline. Stripping the
     * suffix is what lets the phrase table match the form people actually use, and the
     * full name stays as well so both work.
     */
    fun shortName(name: String): String {
        var out = name.trim()
        for (suffix in SUFFIXES) {
            if (out.length <= suffix.length) continue
            if (out.lowercase(Locale.US).endsWith(" $suffix")) {
                out = out.dropLast(suffix.length + 1).trimEnd(',', ' ')
                break
            }
        }
        return out
    }

    /**
     * Splits one CSV row, honouring quotes.
     *
     * Needed for the handful of names carrying a comma inside quotes. A plain split on
     * commas shifts every later column left, which would read a company's series out of
     * its own name and silently drop the row.
     */
    private fun splitRow(line: String): List<String> {
        val out = ArrayList<String>(8)
        val field = StringBuilder()
        var quoted = false
        for (ch in line) {
            when {
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    out.add(field.toString())
                    field.setLength(0)
                }
                else -> field.append(ch)
            }
        }
        out.add(field.toString())
        return out
    }
}

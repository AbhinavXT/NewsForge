package com.abhinavxt.newsforge.core.tag

/**
 * Reads a tab-separated symbol table.
 *
 * Format, one entry per line:
 *
 * ```
 * SYMBOL<TAB>Company Name<TAB>alias one|alias two<TAB>SECTOR
 * ```
 *
 * The third and fourth columns are optional. Blank lines and lines starting with `#` are ignored.
 *
 * A shipped asset rather than generated Kotlin: the NSE universe is a couple of thousand
 * names that change monthly with listings and delistings, and regenerating a source file
 * for that means a code review and a release every time. A data file can be regenerated
 * by a script — see `tools/generate_symbols.py` — and eventually refreshed at runtime.
 */
object SymbolTable {

    private const val COMMENT = '#'
    private const val ALIAS_SEPARATOR = '|'

    fun parse(lines: Sequence<String>): List<SymbolEntry> {
        val entries = ArrayList<SymbolEntry>()
        val seen = HashSet<String>()

        for (raw in lines) {
            // Only the line ending is stripped, never leading whitespace: trimming the
            // whole line would eat a leading tab and shift every column left, turning a
            // row with no symbol into one whose symbol is the company name.
            val line = raw.trimEnd('\r', '\n')
            if (line.isBlank() || line.trimStart().startsWith(COMMENT)) continue

            val columns = line.split('\t')
            val symbol = columns.getOrNull(0)?.trim().orEmpty()
            if (symbol.isEmpty()) continue
            // A duplicate symbol in a generated file means the generator merged two
            // exchanges' rows; keeping the first is the same first-writer-wins rule the
            // lexicon applies to aliases.
            if (!seen.add(symbol)) continue

            val name = columns.getOrNull(1)?.trim().orEmpty().ifEmpty { symbol }
            val aliases = columns.getOrNull(2)?.split(ALIAS_SEPARATOR)
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()

            val sector = columns.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }
            entries += SymbolEntry(symbol, name, aliases, sector)
        }
        return entries
    }

    fun parse(text: String): List<SymbolEntry> = parse(text.lineSequence())

    /** Serialises back to the same format, for the generator script's round-trip test. */
    fun format(entries: List<SymbolEntry>): String = buildString {
        for (entry in entries) {
            append(entry.symbol).append('\t').append(entry.name)
            // A sector with no aliases still needs the empty third column, or it would be
            // read back as an alias list.
            if (entry.aliases.isNotEmpty() || entry.sector != null) {
                append('\t').append(entry.aliases.joinToString(ALIAS_SEPARATOR.toString()))
            }
            entry.sector?.let { append('\t').append(it) }
            append('\n')
        }
    }
}

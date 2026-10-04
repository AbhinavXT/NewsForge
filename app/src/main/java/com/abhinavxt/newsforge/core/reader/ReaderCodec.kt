package com.abhinavxt.newsforge.core.reader

/**
 * Flattens an article's blocks into one string for storage, and back.
 *
 * Not JSON, because the JSON on this platform is Android's `org.json`, which local unit
 * tests cannot run — and a format nobody can test is how saved articles come back
 * scrambled after an update. ASCII's own record and unit separators never occur in
 * article text, so they need no escaping beyond being removed from the text going in.
 */
object ReaderCodec {

    private const val RECORD = '\u001E'
    private const val UNIT = '\u001F'

    fun encode(blocks: List<ReaderBlock>): String =
        blocks.joinToString(RECORD.toString()) { block ->
            val fields = when (block) {
                is ReaderBlock.Paragraph -> listOf("P", block.text)
                is ReaderBlock.Heading -> listOf("H", block.text)
                is ReaderBlock.Quote -> listOf("Q", block.text)
                is ReaderBlock.ListItem -> listOf("L", block.text, block.marker)
                is ReaderBlock.Image -> listOf("I", block.text, block.url)
            }
            fields.joinToString(UNIT.toString()) { clean(it) }
        }

    /** Unknown kinds are skipped: a block written by a newer build is not a reason to lose the rest. */
    fun decode(encoded: String): List<ReaderBlock> {
        if (encoded.isEmpty()) return emptyList()
        return encoded.split(RECORD).mapNotNull { record ->
            val fields = record.split(UNIT)
            val text = fields.getOrElse(1) { "" }
            when (fields[0]) {
                "P" -> ReaderBlock.Paragraph(text)
                "H" -> ReaderBlock.Heading(text)
                "Q" -> ReaderBlock.Quote(text)
                "L" -> ReaderBlock.ListItem(text, fields.getOrElse(2) { "•" })
                "I" -> fields.getOrNull(2)?.takeIf { it.isNotEmpty() }
                    ?.let { ReaderBlock.Image(it, text) }
                else -> null
            }
        }
    }

    private fun clean(text: String): String = text.replace(RECORD, ' ').replace(UNIT, ' ')
}

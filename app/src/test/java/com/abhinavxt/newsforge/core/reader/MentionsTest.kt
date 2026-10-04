package com.abhinavxt.newsforge.core.reader

import com.abhinavxt.newsforge.core.tag.SymbolEntry
import com.abhinavxt.newsforge.core.tag.SymbolLexicon
import com.abhinavxt.newsforge.core.tag.SymbolSpan
import org.junit.Assert.assertEquals
import org.junit.Test

class MentionsTest {

    private val lexicon = SymbolLexicon(
        listOf(
            SymbolEntry("HAL", "Hindustan Aeronautics", listOf("HAL")),
            SymbolEntry("BANKBARODA", "Bank of Baroda"),
        )
    )

    @Test
    fun spansCoverTheWholeNameIncludingDroppedGlueWords() {
        val text = "Shares of Bank of Baroda rose."

        val spans = lexicon.spans(text)

        assertEquals(listOf(SymbolSpan(10, 24, "BANKBARODA")), spans)
        assertEquals("Bank of Baroda", text.substring(spans[0].start, spans[0].end))
    }

    @Test
    fun onlyTheFirstMentionOfEachCompanyIsLinked() {
        val blocks = listOf(
            ReaderBlock.Heading("Hindustan Aeronautics results"),
            ReaderBlock.Paragraph("Hindustan Aeronautics beat estimates."),
            ReaderBlock.Image("https://x/a.jpg", "HAL's plant"),
            ReaderBlock.Paragraph("HAL also said Bank of Baroda lent it money."),
        )

        val mentions = Mentions.firstMentions(blocks) { lexicon.spans(it) }

        assertEquals(setOf(1, 3), mentions.keys)
        assertEquals(listOf("HAL"), mentions.getValue(1).map { it.symbol })
        // HAL is already linked in block 1, so only the bank is new in block 3.
        assertEquals(listOf("BANKBARODA"), mentions.getValue(3).map { it.symbol })
        assertEquals(listOf("HAL", "BANKBARODA"), Mentions.symbols(mentions))
    }

    @Test
    fun matchStillReturnsDistinctSymbolsInOrder() {
        assertEquals(
            listOf("HAL", "BANKBARODA"),
            lexicon.match("HAL and Bank of Baroda; HAL again"),
        )
    }

    @Test
    fun aSingleWordInProseIsOnlyLinkedWhenTheStoryIsTaggedWithIt() {
        val words = SymbolLexicon(
            listOf(
                SymbolEntry("TITAN", "Titan Company", listOf("Titan")),
                SymbolEntry("HAL", "Hindustan Aeronautics", listOf("HAL")),
            )
        )
        val blocks = listOf(ReaderBlock.Paragraph("Titan rose, and HAL fell, said Hindustan Aeronautics."))

        val untagged = Mentions.firstMentions(blocks) { words.spans(it) }
        val tagged = Mentions.firstMentions(blocks, tagged = setOf("TITAN")) { words.spans(it) }

        // "Titan" could be the word; "HAL" in capitals could not.
        assertEquals(listOf("HAL"), Mentions.symbols(untagged))
        assertEquals(listOf("TITAN", "HAL"), Mentions.symbols(tagged))
    }
}

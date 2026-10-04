package com.abhinavxt.newsforge.core.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderCodecTest {

    @Test
    fun everyBlockKindSurvivesARoundTrip() {
        val blocks = listOf(
            ReaderBlock.Heading("Outlook"),
            ReaderBlock.Paragraph("Shares rose 4%, the most in a month."),
            ReaderBlock.Quote("We are confident, said the CEO."),
            ReaderBlock.ListItem("Revenue up 12%", "1."),
            ReaderBlock.Image("https://cdn.example.com/a.jpg?w=600", "The plant in Pune"),
            ReaderBlock.Image("https://cdn.example.com/b.jpg"),
        )

        assertEquals(blocks, ReaderCodec.decode(ReaderCodec.encode(blocks)))
    }

    @Test
    fun separatorsInTheTextCannotSplitABlock() {
        val blocks = listOf(ReaderBlock.Paragraph("a\u001Eb\u001Fc"))

        assertEquals(listOf(ReaderBlock.Paragraph("a b c")), ReaderCodec.decode(ReaderCodec.encode(blocks)))
    }

    @Test
    fun unknownKindsAreSkippedRatherThanFailingTheArticle() {
        val encoded = "P\u001Fone\u001EZ\u001Ffrom a newer build\u001EP\u001Ftwo"

        assertEquals(
            listOf(ReaderBlock.Paragraph("one"), ReaderBlock.Paragraph("two")),
            ReaderCodec.decode(encoded),
        )
        assertEquals(emptyList<ReaderBlock>(), ReaderCodec.decode(""))
    }
}

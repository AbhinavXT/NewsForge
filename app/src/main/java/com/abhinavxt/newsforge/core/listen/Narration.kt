package com.abhinavxt.newsforge.core.listen

/**
 * What listen mode says, and how it is cut up for the speech engine.
 *
 * Free of Android so the wording and the splitting are pinned by tests. Both fail in ways
 * that are only noticed by ear — a headline read twice, a sentence cut in half at a chunk
 * boundary — and nobody listens to a test run.
 */
object Narration {

    /**
     * Comfortably under `TextToSpeech.getMaxSpeechInputLength()`, which is 4000 on every
     * engine seen so far. Text past the limit is not truncated by the engine but rejected
     * outright, which in a queue means silence and a skipped story.
     */
    const val MAX_CHUNK = 3_500

    /**
     * One story, as read in a list: topic, headline, then the summary when it adds
     * anything. The outlet goes last, because a name read before the news is the part a
     * listener tunes out.
     */
    fun story(topic: String, title: String, summary: String?, source: String): String =
        buildString {
            append(sentence(topic))
            append(' ')
            append(sentence(title))
            summary?.takeIf { it.isNotBlank() }?.let {
                append(' ')
                append(sentence(it))
            }
            if (source.isNotBlank()) {
                append(" From ")
                append(sentence(source))
            }
        }

    /**
     * A whole article: headline, then the body in order. Image captions are left out —
     * "Photo: Reuters" read aloud between paragraphs is noise.
     */
    fun article(title: String, site: String?, paragraphs: List<String>): String =
        buildString {
            append(sentence(title))
            site?.takeIf { it.isNotBlank() }?.let { append(" From ").append(sentence(it)) }
            for (paragraph in paragraphs) {
                val text = paragraph.trim()
                if (text.isEmpty()) continue
                append("\n\n")
                append(sentence(text))
            }
        }

    /**
     * Splits [text] into pieces no longer than [max], at the best boundary available:
     * paragraph, then sentence, then word. A hard cut mid-word only for a "word" longer
     * than [max], which in practice is a URL.
     */
    fun chunks(text: String, max: Int = MAX_CHUNK): List<String> {
        val out = ArrayList<String>()
        var rest = text.trim()
        while (rest.length > max) {
            val window = rest.substring(0, max)
            val cut = listOf(
                window.lastIndexOf("\n\n"),
                SENTENCE_END.findAll(window).lastOrNull()?.range?.last?.plus(1) ?: -1,
                window.lastIndexOf(' '),
            ).firstOrNull { it > max / 4 } ?: max
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    /** Ends [text] with a stop so the engine pauses before what follows. */
    private fun sentence(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.isEmpty() || trimmed.last() in ".!?…\"'”’") trimmed else "$trimmed."
    }

    private val SENTENCE_END = Regex("[.!?][\"'”’)]?\\s")
}

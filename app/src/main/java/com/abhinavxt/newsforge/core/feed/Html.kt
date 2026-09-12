package com.abhinavxt.newsforge.core.feed

/**
 * Minimal HTML-to-text for feed fields.
 *
 * `android.text.Html` would do this, but it lives in the Android framework and would drag
 * the whole parsing layer out of local unit tests and into Robolectric. Feed content is
 * simple enough — anchors, paragraphs, entity references — that a real HTML parser is not
 * worth the dependency.
 */
internal object Html {

    private val TAG = Regex("<[^>]{0,2000}>")
    private val WHITESPACE = Regex("[\\s\\u00A0]+")

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
        "ndash" to "\u2013",
        "mdash" to "\u2014",
        "lsquo" to "\u2018",
        "rsquo" to "\u2019",
        "ldquo" to "\u201C",
        "rdquo" to "\u201D",
        "hellip" to "\u2026",
        "rupee" to "\u20B9",
        "middot" to "\u00B7",
        "bull" to "\u2022",
        "eacute" to "\u00E9",
    )

    /**
     * Decodes twice, then strips tags.
     *
     * The double decode is not paranoia: CDATA-wrapped descriptions routinely arrive
     * double-escaped (`&amp;#39;`), and a single pass leaves a literal `&#39;` on screen.
     * Tags are stripped *after* decoding so that an escaped `&lt;b&gt;` is also removed
     * rather than being displayed as markup.
     */
    fun toPlainText(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        var text = decodeEntities(raw)
        text = decodeEntities(text)
        text = TAG.replace(text, " ")
        return WHITESPACE.replace(text, " ").trim()
    }

    private fun decodeEntities(input: String): String {
        if (!input.contains('&')) return input
        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val end = input.indexOf(';', i + 1)
            // Bail out on a bare ampersand or an implausibly long "entity".
            if (end < 0 || end - i > 12) {
                out.append(c)
                i++
                continue
            }
            val body = input.substring(i + 1, end)
            val decoded = decodeOne(body)
            if (decoded == null) {
                out.append(c)
                i++
            } else {
                out.append(decoded)
                i = end + 1
            }
        }
        return out.toString()
    }

    private fun decodeOne(body: String): String? {
        if (body.isEmpty()) return null
        if (body[0] == '#') {
            val digits = body.substring(1)
            val code = if (digits.startsWith("x") || digits.startsWith("X")) {
                digits.drop(1).toIntOrNull(16)
            } else {
                digits.toIntOrNull()
            } ?: return null
            if (code !in 1..0x10FFFF) return null
            return String(Character.toChars(code))
        }
        return NAMED_ENTITIES[body.lowercase()]
    }
}

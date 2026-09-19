package com.abhinavxt.newsforge.core.desk

import java.util.Locale

/**
 * One message pushed from your own machine.
 *
 * Deliberately not an article, and deliberately in its own package. A TickerForge alert is
 * something you asked a system you control to tell you; a news story is something a
 * publisher decided to print. Letting the first compete with the second for a slot in the
 * brief would be wrong in both directions — your own alert would be ranked by a model
 * built for headlines, and it would displace news it has nothing to do with.
 */
data class DeskMessage(
    val id: String,
    val topic: String,
    val title: String?,
    val body: String,
    /** ntfy's 1–5 scale; 3 is its default. */
    val priority: Int,
    val tags: List<String>,
    val clickUrl: String?,
    val receivedAtMillis: Long,
    /**
     * Parsed on read, not stored: the raw body is already the source of truth.
     *
     * A list because one message can carry several symbols — see
     * [DeskPayload.KIND_QUOTE_BATCH]. A single quote is a list of one, so nothing that
     * handles the common case has to know the batch exists.
     */
    val payloads: List<DeskPayload> = emptyList(),
) {
    /** The first payload, for callers that only ever expect one. */
    val payload: DeskPayload? get() = payloads.firstOrNull()

    /** 4 and 5 are ntfy's "high" and "max". */
    val isHighPriority: Boolean get() = priority >= 4

    /** First line of the body, for a collapsed list row. */
    val summary: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}

/**
 * Shapes decoded ntfy records.
 *
 * JSON decoding lives on the Android side; everything judgemental is here so it can be
 * tested without a parser on the classpath — which matters, because the awkward parts are
 * all judgement: which events to ignore, what a missing priority means, and how to get a
 * usable timestamp out of a field measured in seconds.
 */
object NtfyMessages {

    /**
     * ntfy's stream carries control frames alongside messages.
     *
     * `keepalive` arrives every 45 seconds or so and `open` on every connection. Storing
     * either would fill the list with blanks, so only `message` survives.
     */
    const val EVENT_MESSAGE = "message"

    private const val DEFAULT_PRIORITY = 3

    fun fromRecord(record: Map<String, String?>, fallbackMillis: Long): DeskMessage? {
        val event = record["event"]?.trim()?.lowercase(Locale.US) ?: EVENT_MESSAGE
        if (event != EVENT_MESSAGE) return null

        val id = record["id"]?.trim().orEmpty()
        if (id.isEmpty()) return null

        val body = record["message"]?.trim().orEmpty()
        val title = record["title"]?.trim()?.takeIf { it.isNotEmpty() }
        // A frame with neither is a control frame that mislabelled itself; nothing to show.
        if (body.isEmpty() && title == null) return null

        return DeskMessage(
            id = id,
            topic = record["topic"]?.trim().orEmpty(),
            title = title,
            body = body,
            priority = record["priority"]?.trim()?.toIntOrNull()?.coerceIn(1, 5)
                ?: DEFAULT_PRIORITY,
            tags = record["tags"]?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty(),
            clickUrl = record["click"]?.trim()?.takeIf { it.startsWith("http") },
            // ntfy reports seconds; everything else in this app is millis, and mixing the
            // two would date every message to 1970.
            receivedAtMillis = record["time"]?.trim()?.toLongOrNull()?.times(1000)
                ?: fallbackMillis,
        )
    }

    fun fromRecords(records: List<Map<String, String?>>, fallbackMillis: Long): List<DeskMessage> =
        records.mapNotNull { fromRecord(it, fallbackMillis) }

    /**
     * Builds the poll URL.
     *
     * `poll=1` asks for what is already queued and returns immediately rather than holding
     * the connection open. A persistent stream would deliver instantly but needs a
     * foreground service and a permanent notification to survive Doze — too much standing
     * cost for a bridge. Instant delivery is what the ntfy app itself is for; this is for
     * having the history in one place.
     */
    /**
     * Where a message is sent.
     *
     * The same topic the app polls, which is what makes this a conversation rather than
     * two channels: the desk already listens there, and anything sent comes back on the
     * next poll alongside whatever it replied.
     */
    fun publishUrl(server: String, topic: String): String {
        val base = server.trim().trimEnd('/').ifEmpty { "https://ntfy.sh" }
        return "$base/${topic.trim()}"
    }

    fun pollUrl(server: String, topic: String, sinceSeconds: Long?): String {
        val base = server.trim().trimEnd('/').ifEmpty { "https://ntfy.sh" }
        val since = sinceSeconds?.let { it.coerceAtLeast(0) }?.toString() ?: "12h"
        return "$base/${topic.trim()}/json?poll=1&since=$since"
    }
}

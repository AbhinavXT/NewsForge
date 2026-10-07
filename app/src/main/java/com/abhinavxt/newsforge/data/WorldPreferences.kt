package com.abhinavxt.newsforge.data

import android.content.Context
import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.world.Keywords
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How one reader has arranged the World tab.
 *
 * @param topics every world topic in the reader's order, hidden ones included, so
 *   un-hiding a topic puts it back where it was rather than at the end.
 */
data class WorldSettings(
    val topics: List<Category> = Category.WORLD_TOPICS,
    val hidden: Set<Category> = emptySet(),
    val keywords: List<String> = emptyList(),
    val keywordAlerts: Boolean = true,
    val digestEnabled: Boolean = true,
    /** Local hour, 0–23, after which the evening digest may be sent. */
    val digestHour: Int = DEFAULT_DIGEST_HOUR,
) {
    /** Topics to show, in order. */
    val visibleTopics: List<Category> get() = topics.filterNot { it in hidden }

    companion object {
        const val DEFAULT_DIGEST_HOUR = 19
    }
}

/**
 * World tab settings, on SharedPreferences.
 *
 * Held behind a [StateFlow] as well as written through, because the tab, its settings
 * sheet and the background worker all read it, and the tab has to redraw the moment a
 * topic is hidden — not on the next launch.
 */
class WorldPreferences(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("world", Context.MODE_PRIVATE)

    private val state = MutableStateFlow(read())

    val settings: StateFlow<WorldSettings> = state.asStateFlow()

    fun current(): WorldSettings = state.value

    fun setHidden(topic: Category, hidden: Boolean) = update {
        it.copy(hidden = if (hidden) it.hidden + topic else it.hidden - topic)
    }

    /** Moves [topic] one place up (negative [by]) or down. */
    fun move(topic: Category, by: Int) = update {
        val order = it.topics.toMutableList()
        val from = order.indexOf(topic)
        val to = (from + by).coerceIn(0, order.lastIndex)
        if (from < 0 || from == to) return@update it
        order.removeAt(from)
        order.add(to, topic)
        it.copy(topics = order)
    }

    fun resetTopics() = update { it.copy(topics = Category.WORLD_TOPICS, hidden = emptySet()) }

    /** @return false when the keyword was unusable, a duplicate or over the limit. */
    fun addKeyword(raw: String): Boolean {
        val keyword = Keywords.normalize(raw) ?: return false
        val current = state.value.keywords
        if (current.size >= Keywords.MAX_COUNT) return false
        if (current.any { it.equals(keyword, ignoreCase = true) }) return false
        update { it.copy(keywords = it.keywords + keyword) }
        return true
    }

    fun removeKeyword(keyword: String) = update { it.copy(keywords = it.keywords - keyword) }

    fun setKeywordAlerts(enabled: Boolean) = update { it.copy(keywordAlerts = enabled) }

    fun setDigestEnabled(enabled: Boolean) = update { it.copy(digestEnabled = enabled) }

    fun setDigestHour(hour: Int) = update { it.copy(digestHour = hour.coerceIn(0, 23)) }

    /**
     * The local date (epoch day) the evening digest last went out for.
     *
     * Kept out of [WorldSettings]: it is the worker's bookkeeping, not anything the
     * reader set, and putting it in the flow would redraw the tab every evening.
     */
    var lastDigestDay: Long
        get() = prefs.getLong(KEY_LAST_DIGEST_DAY, -1L)
        set(value) = prefs.edit().putLong(KEY_LAST_DIGEST_DAY, value).apply()

    private fun update(change: (WorldSettings) -> WorldSettings) {
        val next = change(state.value)
        if (next == state.value) return
        state.value = next
        prefs.edit()
            .putString(KEY_ORDER, next.topics.joinToString(",") { it.name })
            .putStringSet(KEY_HIDDEN, next.hidden.mapTo(HashSet()) { it.name })
            .putString(KEY_KEYWORDS, next.keywords.joinToString(KEYWORD_SEPARATOR))
            .putBoolean(KEY_KEYWORD_ALERTS, next.keywordAlerts)
            .putBoolean(KEY_DIGEST, next.digestEnabled)
            .putInt(KEY_DIGEST_HOUR, next.digestHour)
            .apply()
    }

    private fun read(): WorldSettings {
        val byName = Category.WORLD_TOPICS.associateBy { it.name }
        val stored = prefs.getString(KEY_ORDER, null)
            ?.split(',')
            ?.mapNotNull { byName[it] }
            .orEmpty()
            .distinct()
        // Topics added in an update after the order was saved go on the end, so a new
        // topic appears for an existing reader instead of being silently missing.
        val order = stored + Category.WORLD_TOPICS.filterNot { it in stored }
        return WorldSettings(
            topics = order,
            hidden = prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
                .mapNotNullTo(HashSet()) { byName[it] },
            keywords = prefs.getString(KEY_KEYWORDS, null)
                ?.split(KEYWORD_SEPARATOR)
                ?.filter { it.isNotBlank() }
                .orEmpty(),
            keywordAlerts = prefs.getBoolean(KEY_KEYWORD_ALERTS, true),
            digestEnabled = prefs.getBoolean(KEY_DIGEST, true),
            digestHour = prefs.getInt(KEY_DIGEST_HOUR, WorldSettings.DEFAULT_DIGEST_HOUR),
        )
    }

    private companion object {
        const val KEY_ORDER = "topic_order"
        const val KEY_HIDDEN = "hidden_topics"
        const val KEY_KEYWORDS = "keywords"
        const val KEY_KEYWORD_ALERTS = "keyword_alerts"
        const val KEY_DIGEST = "digest_enabled"
        const val KEY_DIGEST_HOUR = "digest_hour"
        const val KEY_LAST_DIGEST_DAY = "last_digest_day"

        /** A unit separator: cannot be typed into the field, so cannot be in a keyword. */
        const val KEYWORD_SEPARATOR = "\u001F"
    }
}

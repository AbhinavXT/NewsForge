package com.abhinavxt.newsforge.data

import android.content.Context

/**
 * Bridge configuration.
 *
 * On SharedPreferences rather than in the database, and separate from every other store in
 * the app: this is credentials and an endpoint, not data.
 */
class DeskPreferences(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("desk", Context.MODE_PRIVATE)

    var server: String
        get() = prefs.getString(KEY_SERVER, DEFAULT_SERVER).orEmpty().ifEmpty { DEFAULT_SERVER }
        set(value) = prefs.edit().putString(KEY_SERVER, value.trim()).apply()

    var topic: String
        get() = prefs.getString(KEY_TOPIC, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TOPIC, value.trim()).apply()

    /** Optional ntfy access token, for a private or self-hosted server. */
    var token: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /** The bridge is off until a topic is set; there is nothing to poll without one. */
    val isConfigured: Boolean get() = topic.isNotBlank()

    companion object {
        const val DEFAULT_SERVER = "https://ntfy.sh"
        private const val KEY_SERVER = "server"
        private const val KEY_TOPIC = "topic"
        private const val KEY_TOKEN = "token"
        private const val KEY_COMMANDS = "commands"
        private const val KEY_COMMAND_TOPIC = "commandTopic"

        /** A newline cannot appear in a single-line command, so it cannot corrupt the list. */
        private const val SEPARATOR = "\n"
        private const val MAX_COMMANDS = 8
    }
    /**
     * Where commands are sent, when that is not where replies arrive.
     *
     * ntfy topics are one-way in practice: a desk that subscribes to a topic and also
     * publishes to it would hear its own output, so the sensible arrangement is two —
     * one the desk listens on, one it talks on. The app had only ever needed the second,
     * so `send` published into the channel it was reading and nothing was listening.
     *
     * Blank means both directions use [topic], which is right for a bridge that does use
     * one topic and keeps existing setups working untouched.
     */
    var commandTopic: String
        get() = prefs.getString(KEY_COMMAND_TOPIC, null)?.trim().orEmpty()
        set(value) = prefs.edit().putString(KEY_COMMAND_TOPIC, value.trim()).apply()

    /** The topic `send` should publish to. */
    val outboundTopic: String
        get() = commandTopic.ifBlank { topic }

    /**
     * The last few things sent, newest first.
     *
     * Commands are short, repeated and awkward to type on a phone — and their spelling
     * belongs to the desk, so a mistyped one fails silently by simply not being
     * understood. Keeping what has worked is cheaper than remembering it.
     */
    var recentCommands: List<String>
        get() = prefs.getString(KEY_COMMANDS, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()
        set(value) {
            prefs.edit()
                .putString(KEY_COMMANDS, value.take(MAX_COMMANDS).joinToString(SEPARATOR))
                .apply()
        }

    /** Moves a command to the front, or adds it. */
    fun rememberCommand(command: String) {
        val line = command.trim()
        if (line.isEmpty() || SEPARATOR in line) return
        recentCommands = (listOf(line) + recentCommands.filterNot { it == line })
    }

}

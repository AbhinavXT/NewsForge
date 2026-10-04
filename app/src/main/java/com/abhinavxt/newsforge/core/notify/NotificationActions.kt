package com.abhinavxt.newsforge.core.notify

/** Something the reader can do to a story without opening the app. */
enum class AlertAction {
    /** Add the story's company to the watchlist at the default tier. */
    WATCH,

    /** Open the company's research screen rather than the article. */
    CHART,

    /** Silence the outlet, everywhere, until the rule is removed. */
    MUTE_SOURCE,

    /** Keep the story to read later. */
    SAVE,

    /** Mark every story under a summary read, and clear them from the shade. */
    MARK_ALL_READ,

    /** Arm a fired price alert again, for a level that is worth hearing about twice. */
    REARM_PRICE,
}

/**
 * One button, already worded.
 *
 * The label is built here rather than at the call site so that the decision and its
 * wording stay together: "Watch INFY" is only correct while INFY is the symbol the
 * button will actually act on, and separating the two is how those drift apart.
 */
data class AlertActionButton(
    val action: AlertAction,
    val label: String,
    /** Set for [AlertAction.WATCH] and [AlertAction.CHART]. */
    val symbol: String? = null,
    /** Set for [AlertAction.MUTE_SOURCE]. */
    val source: String? = null,
)

/**
 * Picks the buttons for a story alert.
 *
 * The question a market notification has to answer at 09:20 is "does this change what I
 * do today", and the answers are: follow this name, look at its chart, or stop this
 * outlet from doing that to me again. Those are the buttons.
 *
 * Android renders three at most and silently drops the rest, so the order here is
 * significance rather than taste — the first is the one the reader loses if a launcher
 * shows fewer.
 *
 * Pure, and separate from [com.abhinavxt.newsforge.notify.Notifier], because the
 * interesting part is which buttons appear and that would otherwise only be observable
 * by reading a real notification off a real device.
 */
object NotificationActions {

    /** Android's ceiling. More are accepted by the builder and never drawn. */
    const val MAX_BUTTONS = 3

    /**
     * Outlet names longer than this crowd out the other buttons, and a truncated
     * "Mute The Hindu Busin…" is less legible than the generic wording.
     */
    private const val MAX_SOURCE_LABEL = 14

    /**
     * @param symbols companies tagged on the story, most confident first.
     * @param sourceName the outlet as shown in the byline.
     * @param followed true when the leading symbol is already on the watchlist, which is
     *   what turns the Watch button from useful into a no-op with a label.
     */
    fun forAlert(
        symbols: List<String>,
        sourceName: String,
        followed: Boolean,
    ): List<AlertActionButton> {
        // The first tag, not all of them. A story naming six companies is a market
        // round-up, and a button that silently picked one of the six would be worse than
        // no button — the reader would have to open the app to find out what it did.
        val symbol = symbols.firstOrNull()?.takeIf { it.isNotBlank() }
        val source = sourceName.trim().takeIf { it.isNotEmpty() }

        val buttons = buildList {
            if (symbol != null && !followed) {
                add(AlertActionButton(AlertAction.WATCH, "Watch $symbol", symbol = symbol))
            }
            if (symbol != null) {
                add(AlertActionButton(AlertAction.CHART, "Chart", symbol = symbol))
            }
            if (source != null) {
                val label =
                    if (source.length > MAX_SOURCE_LABEL) "Mute source" else "Mute $source"
                add(AlertActionButton(AlertAction.MUTE_SOURCE, label, source = source))
            }
            // Last, and therefore only shown when something above was unavailable. Saving
            // is the least urgent of the four: it is the one action whose moment is not
            // now, and tapping the notification already opens the story.
            add(AlertActionButton(AlertAction.SAVE, "Save"))
        }
        return buttons.take(MAX_BUTTONS)
    }
}

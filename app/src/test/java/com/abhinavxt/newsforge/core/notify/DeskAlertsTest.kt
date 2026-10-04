package com.abhinavxt.newsforge.core.notify

import com.abhinavxt.newsforge.core.desk.DeskMessage
import com.abhinavxt.newsforge.core.desk.DeskPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskAlertsTest {

    private val now = 1_789_600_000_000L

    private fun message(
        id: String = "m1",
        priority: Int = 5,
        ageMinutes: Long = 1,
        payloads: List<DeskPayload> = emptyList(),
    ) = DeskMessage(
        id = id,
        topic = "t",
        title = "Strategy signals",
        body = "Long BEL above 412",
        priority = priority,
        tags = emptyList(),
        clickUrl = null,
        receivedAtMillis = now - ageMinutes * 60 * 1000,
        payloads = payloads,
    )

    private fun select(
        messages: List<DeskMessage>,
        notified: Set<String> = emptySet(),
        quiet: Boolean = false,
        enabled: Boolean = true,
    ) = DeskAlerts.select(messages, notified, now, quiet, enabled)

    @Test
    fun theDeskDecidesWhatIsUrgent() {
        // Priority is the whole rule. The sender knows which of its own messages matter
        // and this app does not; reading the body to guess would be a second opinion on a
        // question already answered upstream.
        assertEquals(1, select(listOf(message(priority = 4))).size)
        assertEquals(1, select(listOf(message(priority = 5))).size)
        assertTrue(select(listOf(message(priority = 3))).isEmpty())
        assertTrue(select(listOf(message(priority = 1))).isEmpty())
    }

    @Test
    fun aQuoteBatchIsNeverASignal() {
        // A misconfigured sender pushing prices at high priority would train the reader
        // to dismiss the channel, which costs the real signals their only advantage.
        val quotes = listOf(DeskPayload(symbol = "BEL", kind = "quote", timestampMillis = now))
        assertTrue(select(listOf(message(payloads = quotes))).isEmpty())
    }

    @Test
    fun aMessageAnnouncedBeforeIsNotAnnouncedAgain() {
        // The poll overlaps its watermark on purpose, so most batches re-deliver
        // something already held.
        assertTrue(select(listOf(message(id = "m1")), notified = setOf("desk:m1")).isEmpty())
    }

    @Test
    fun theKeyIsNamespacedAwayFromClusterIds() {
        // The notified table is shared with the news alerts.
        assertTrue(DeskAlerts.notifiedKey(message(id = "abc")).startsWith("desk:"))
    }

    @Test
    fun aPhoneWakingUpDoesNotDeliverHoursOfSignalsAtOnce() {
        assertTrue(select(listOf(message(ageMinutes = 240))).isEmpty())
        assertEquals(1, select(listOf(message(ageMinutes = 10))).size)
    }

    @Test
    fun quietHoursAndTheAlertToggleBothSilenceIt() {
        // A trade signal at 03:00 is no more actionable than a filing at 03:00.
        assertTrue(select(listOf(message()), quiet = true).isEmpty())
        assertTrue(select(listOf(message()), enabled = false).isEmpty())
    }

    @Test
    fun aBurstIsCappedAndTheNewestSurvive() {
        val messages = (1..10).map { message(id = "m$it", ageMinutes = it.toLong()) }
        val chosen = select(messages)
        assertEquals(DeskAlerts.MAX_PER_SYNC, chosen.size)
        assertEquals(listOf("m1", "m2", "m3"), chosen.map { it.id })
    }
}

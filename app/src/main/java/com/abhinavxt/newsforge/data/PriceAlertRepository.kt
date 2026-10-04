package com.abhinavxt.newsforge.data

import com.abhinavxt.newsforge.core.quote.PriceAlertKind
import com.abhinavxt.newsforge.core.quote.PriceAlertRule
import com.abhinavxt.newsforge.data.db.PriceAlertDao
import com.abhinavxt.newsforge.data.db.PriceAlertEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A price alert as the screens show it: armed, or fired and when. */
data class PriceAlertItem(
    val rule: PriceAlertRule,
    val triggeredAt: Long?,
    val triggeredPrice: Double?,
)

/** The price levels the reader has asked to hear about. */
class PriceAlertRepository(
    private val dao: PriceAlertDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun alertsFor(symbol: String): Flow<List<PriceAlertItem>> =
        dao.observeFor(symbol).map { rows -> rows.mapNotNull { it.toItem() } }

    fun all(): Flow<List<PriceAlertItem>> = dao.observeAll().map { rows -> rows.mapNotNull { it.toItem() } }

    suspend fun armed(): List<PriceAlertRule> = dao.armed().mapNotNull { it.toItem()?.rule }

    suspend fun add(symbol: String, kind: PriceAlertKind, threshold: Double) {
        dao.add(
            PriceAlertEntity(
                symbol = symbol.uppercase(),
                kind = kind.name,
                threshold = threshold,
                createdAt = clock(),
            )
        )
    }

    /** @return false when another poller already fired it, so it must not ring twice. */
    suspend fun markTriggered(id: Long, price: Double): Boolean =
        dao.markTriggered(id, clock(), price) > 0

    suspend fun remove(id: Long) = dao.remove(id)

    suspend fun rearm(id: Long) = dao.rearm(id)

    /** A kind this build does not know is skipped rather than guessed at. */
    private fun PriceAlertEntity.toItem(): PriceAlertItem? {
        val kind = PriceAlertKind.parse(kind) ?: return null
        return PriceAlertItem(PriceAlertRule(id, symbol, kind, threshold), triggeredAt, triggeredPrice)
    }
}

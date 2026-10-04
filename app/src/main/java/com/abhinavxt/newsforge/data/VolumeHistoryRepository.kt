package com.abhinavxt.newsforge.data

import android.util.Log
import com.abhinavxt.newsforge.core.desk.DeskPayload
import com.abhinavxt.newsforge.core.quote.VolumeCurve
import com.abhinavxt.newsforge.core.quote.VolumeProfile
import com.abhinavxt.newsforge.core.quote.VolumeSample
import com.abhinavxt.newsforge.data.db.VolumeSampleDao
import com.abhinavxt.newsforge.data.db.VolumeSampleEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Learns what a normal session looks like for each name it sees.
 *
 * Volume only means something against the same stock at the same point of the same kind of
 * day, and no endpoint publishes that. It has to be accumulated — which the app is well
 * placed to do, since it is already asking for volume every minute through the session and
 * throwing it away.
 *
 * Silent at first, deliberately. Fewer than a handful of prior sessions is not a baseline,
 * and publishing a ratio derived from two days would make every Monday after a quiet
 * Friday look like a breakout. It fills in over a fortnight; a desk-supplied `rel_volume`
 * computed against twenty real sessions is better and takes precedence the moment it
 * arrives.
 */
class VolumeHistoryRepository(
    private val dao: VolumeSampleDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val state = MutableStateFlow<Map<String, Double>>(emptyMap())

    /** Relative volume per symbol at the current mark of the session. Empty out of hours. */
    fun relativeVolumes(): StateFlow<Map<String, Double>> = state.asStateFlow()

    /**
     * Today's volume shape for one company, against its own normal.
     *
     * Reads a month of sessions rather than the [VolumeProfile.MIN_PRIOR_SESSIONS] the
     * ratio needs: the median is steadier over twenty days than over five, and the rows
     * are twenty-five small numbers a session, so the wider window costs nothing worth
     * counting.
     */
    fun curve(symbol: String): Flow<VolumeCurve?> {
        val wanted = symbol.trim().uppercase()
        return dao.observeForSymbol(wanted, VolumeProfile.sessionDay(clock()) - CURVE_WINDOW_DAYS)
            .map { rows ->
                VolumeProfile.curve(
                    samples = rows.map {
                        VolumeSample(it.symbol, it.sessionDay, it.bucket, it.volume)
                    },
                    todayDay = VolumeProfile.sessionDay(clock()),
                )
            }
            .flowOn(Dispatchers.Default)
    }

    /**
     * Records this poll's volumes and recomputes the ratios.
     *
     * Cumulative figures, so writing the same mark twice is a correction rather than a
     * duplicate — the later reading of a bucket is simply the better one.
     */
    suspend fun record(quotes: Collection<DeskPayload>) {
        if (quotes.isEmpty()) return
        val now = clock()
        val bucket = VolumeProfile.bucketOf(now) ?: run {
            // Outside the session the question has no answer. Publishing yesterday's
            // ratios into this evening's feed would be worse than publishing none.
            state.value = emptyMap()
            return
        }
        val today = VolumeProfile.sessionDay(now)

        withContext(ioDispatcher) {
            val rows = quotes.mapNotNull { quote ->
                val volume = quote.volume?.toDouble()?.takeIf { it > 0.0 } ?: return@mapNotNull null
                VolumeSampleEntity(quote.symbol, today, bucket, volume)
            }
            if (rows.isEmpty()) return@withContext
            dao.upsertAll(rows)

            val history = dao.atBucket(bucket, today - KEEP_SESSIONS)
                .groupBy { it.symbol }
            state.value = buildMap {
                for (row in rows) {
                    val priors = history[row.symbol]
                        .orEmpty()
                        // Today is the measurement, not part of its own baseline.
                        .filter { it.sessionDay != today }
                        .map { it.volume }
                    VolumeProfile.relative(row.volume, priors)?.let { put(row.symbol, it) }
                }
            }
            dao.pruneBefore(today - KEEP_SESSIONS)
        }
    }

    private companion object {
        const val TAG = "VolumeHistory"

        /**
         * Sessions of history kept, counted in calendar days so weekends and holidays are
         * simply absent rather than needing a trading calendar to skip.
         */
        const val KEEP_SESSIONS = 30L
    }
}

/** Sessions of history read for the curve; a month of trading days with room to spare. */
private const val CURVE_WINDOW_DAYS = 45L

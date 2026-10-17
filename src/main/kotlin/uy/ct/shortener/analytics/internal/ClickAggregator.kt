package uy.ct.shortener.analytics.internal

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import uy.ct.shortener.shortlink.ShortCode
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/** The key of a count: a link, the hour (seconds since the epoch, divided by 3,600) and the class of device. */
data class ClickKey(val shortCode: String, val epochHour: Long, val device: DeviceClass) {

    val epochSecond: Long get() = epochHour * SECONDS_PER_HOUR

    private companion object {
        const val SECONDS_PER_HOUR = 3_600L
    }
}

data class ClickCount(val key: ClickKey, val clicks: Long)

/**
 * The counts of clicks an instance holds until they are flushed to the database.
 *
 * - [record] adds one to the count of its (link, hour, class) in one atomic step, so concurrent redirects never lose a click.
 * - It holds about `maxEntries` distinct keys. A click that would need another is dropped and counted in
 *   `analytics.clicks.dropped{reason=full}`. A key already held always counts. The check and the insert are not one step, so
 *   concurrent clicks for new keys can pass the cap, by at most the number of redirects in flight.
 * - [drain] takes counts out, up to a limit, and [putBack] returns what could not be written. What no longer fits is dropped and
 *   counted in `analytics.clicks.dropped{reason=storage}`.
 * - Metrics: `analytics.clicks.recorded`, `analytics.clicks.dropped` and the gauge `analytics.aggregator.entries`.
 */
class ClickAggregator(
    private val maxEntries: Int,
    private val clock: Clock,
    registry: MeterRegistry,
) : ClickRecorder {

    private val counts = ConcurrentHashMap<ClickKey, Long>()

    private val recorded = registry.counter("analytics.clicks.recorded")
    private val droppedFull = registry.counter("analytics.clicks.dropped", "reason", "full")
    private val droppedStorage = registry.counter("analytics.clicks.dropped", "reason", "storage")

    init {
        Gauge.builder("analytics.aggregator.entries", counts) { it.size.toDouble() }.register(registry)
    }

    val size: Int get() = counts.size

    override fun record(shortCode: ShortCode, device: DeviceClass) {
        val key = ClickKey(shortCode.value, clock.instant().epochSecond / SECONDS_PER_HOUR, device)
        if (add(key, 1)) recorded.increment() else droppedFull.increment()
    }

    fun drain(limit: Int): List<ClickCount> {
        val batch = ArrayList<ClickCount>(minOf(limit, counts.size))
        val keys = counts.keys.iterator()
        while (batch.size < limit && keys.hasNext()) {
            val key = keys.next()
            counts.remove(key)?.let { batch += ClickCount(key, it) }
        }
        return batch
    }

    fun putBack(batch: List<ClickCount>) {
        val lost = batch.filterNot { add(it.key, it.clicks) }.sumOf { it.clicks }
        if (lost > 0) droppedStorage.increment(lost.toDouble())
    }

    private fun add(key: ClickKey, clicks: Long): Boolean {
        var accepted = true
        counts.compute(key) { _, current ->
            when {
                current != null -> current + clicks
                counts.size >= maxEntries -> null.also { accepted = false }
                else -> clicks
            }
        }
        return accepted
    }

    private companion object {
        const val SECONDS_PER_HOUR = 3_600L
    }
}

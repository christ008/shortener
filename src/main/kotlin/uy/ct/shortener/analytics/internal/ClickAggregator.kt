package uy.ct.shortener.analytics.internal

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import uy.ct.shortener.shortlink.ShortCode
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
 * - It holds at most `maxEntries` distinct keys. A click that would need another is dropped and counted in
 *   `analytics.clicks.dropped{reason=full}`. A key already held always counts. The keys are counted with an atomic counter, taken
 *   and given back with the insert and the removal, not with the map's `size`, which is an estimate while other threads change the
 *   map: it let concurrent new keys pass the cap, and could in principle report it full when it was not.
 * - [drain] takes counts out, up to a limit, each key once. A key counted again while a drain walks the map can be met twice by the
 *   walk, so a drain adds the two up: the store writes a batch in one statement, which cannot change a row twice.
 * - [putBack] returns what could not be written. What no longer fits is dropped and counted in `analytics.clicks.dropped{reason=storage}`.
 * - Metrics: `analytics.clicks.recorded`, `analytics.clicks.dropped` and the gauge `analytics.aggregator.entries`.
 */
class ClickAggregator(
    private val maxEntries: Int,
    private val clock: Clock,
    registry: MeterRegistry,
) : ClickRecorder {

    private val counts = ConcurrentHashMap<ClickKey, Long>()

    private val entries = AtomicInteger()

    private val recorded = registry.counter("analytics.clicks.recorded")
    private val droppedFull = registry.counter("analytics.clicks.dropped", "reason", "full")
    private val droppedStorage = registry.counter("analytics.clicks.dropped", "reason", "storage")

    init {
        Gauge.builder("analytics.aggregator.entries", entries) { it.get().toDouble() }.register(registry)
    }

    val size: Int get() = entries.get()

    override fun record(shortCode: ShortCode, device: DeviceClass) {
        val key = ClickKey(shortCode.value, clock.instant().epochSecond / SECONDS_PER_HOUR, device)
        if (add(key, 1)) recorded.increment() else droppedFull.increment()
    }

    fun drain(limit: Int): List<ClickCount> {
        val batch = HashMap<ClickKey, Long>()
        val keys = counts.keys.iterator()
        while (batch.size < limit && keys.hasNext()) {
            val key = keys.next()
            counts.remove(key)?.let { clicks ->
                entries.decrementAndGet()
                batch.merge(key, clicks, Long::plus)
            }
        }
        return batch.map { (key, clicks) -> ClickCount(key, clicks) }
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

                entries.incrementAndGet() > maxEntries -> null.also {
                    entries.decrementAndGet()
                    accepted = false
                }

                else -> clicks
            }
        }
        return accepted
    }

    private companion object {
        const val SECONDS_PER_HOUR = 3_600L
    }
}

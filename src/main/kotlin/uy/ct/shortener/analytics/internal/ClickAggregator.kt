package uy.ct.shortener.analytics.internal

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import uy.ct.shortener.analytics.Language
import uy.ct.shortener.analytics.Referrer
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a count is kept under. Each click adds to an [Hourly] count and, for a visitor, to a [ReferrerDaily] and a [LanguageDaily] one.
 *
 * - An hour and a day are numbered from the epoch in UTC: seconds divided by 3,600 or by 86,400.
 */
sealed interface CountKey {

    val shortCode: String

    data class Hourly(override val shortCode: String, val epochHour: Long, val device: DeviceClass) : CountKey {
        val epochSecond: Long get() = epochHour * SECONDS_PER_HOUR
    }

    data class ReferrerDaily(override val shortCode: String, val epochDay: Long, val referrer: Referrer) : CountKey

    data class LanguageDaily(override val shortCode: String, val epochDay: Long, val language: Language) : CountKey

    companion object {
        const val SECONDS_PER_HOUR = 3_600L
        const val SECONDS_PER_DAY = 86_400L
    }
}

data class ClickCount(val key: CountKey, val clicks: Long)

/**
 * The counts of clicks an instance holds until they are flushed to the database.
 *
 * - [record] adds one to the count of the click's (link, hour, class), and for a visitor, not a bot, to the counts of its referrer and
 *   its language that day. Each add is one atomic step, so concurrent redirects never lose a click. The referrer is the one the
 *   [ReferrerAdmission] admits.
 * - It holds at most `maxEntries` distinct keys. A count that would need another is dropped and counted in
 *   `analytics.clicks.dropped{reason=full}`, once per count, so a visitor's click can be counted there up to three times. A key already
 *   held always counts. The keys are counted with an atomic counter, taken and given back with the insert and the removal, not with the
 *   map's `size`, which is an estimate while other threads change the map: it let concurrent new keys pass the cap, and could in
 *   principle report it full when it was not.
 * - [drain] takes counts out, up to a limit, each key once. A key counted again while a drain walks the map can be met twice by the
 *   walk, so a drain adds the two up: the store writes a batch in one statement, which cannot change a row twice.
 * - [putBack] returns what could not be written. What no longer fits is dropped and counted in `analytics.clicks.dropped{reason=storage}`.
 * - Metrics: `analytics.clicks.recorded` (clicks whose hourly count was kept), `analytics.clicks.dropped` and the gauge
 *   `analytics.aggregator.entries`.
 */
class ClickAggregator(
    private val maxEntries: Int,
    private val clock: Clock,
    private val referrers: ReferrerAdmission,
    registry: MeterRegistry,
) : ClickRecorder {

    private val counts = ConcurrentHashMap<CountKey, Long>()

    private val entries = AtomicInteger()

    private val recorded = registry.counter("analytics.clicks.recorded")
    private val droppedFull = registry.counter("analytics.clicks.dropped", "reason", "full")
    private val droppedStorage = registry.counter("analytics.clicks.dropped", "reason", "storage")

    init {
        Gauge.builder("analytics.aggregator.entries", entries) { it.get().toDouble() }.register(registry)
    }

    val size: Int get() = entries.get()

    override fun record(click: Click) {
        val second = clock.instant().epochSecond
        val code = click.shortCode.value
        if (count(CountKey.Hourly(code, second / CountKey.SECONDS_PER_HOUR, click.device))) recorded.increment()
        when (click.device) {
            DeviceClass.BOT -> Unit

            DeviceClass.DESKTOP, DeviceClass.MOBILE -> {
                val day = second / CountKey.SECONDS_PER_DAY
                count(CountKey.ReferrerDaily(code, day, referrers.admit(code, day, click.referrer)))
                count(CountKey.LanguageDaily(code, day, click.language))
            }
        }
    }

    private fun count(key: CountKey): Boolean = add(key, 1).also { if (!it) droppedFull.increment() }

    fun drain(limit: Int): List<ClickCount> {
        val batch = HashMap<CountKey, Long>()
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

    private fun add(key: CountKey, clicks: Long): Boolean {
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
}

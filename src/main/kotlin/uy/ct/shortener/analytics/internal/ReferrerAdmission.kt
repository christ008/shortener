package uy.ct.shortener.analytics.internal

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.micrometer.core.instrument.MeterRegistry
import uy.ct.shortener.analytics.Referrer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Bounds the referrer hosts an instance counts for a link in a day, so a forged `Referer` cannot grow the daily table.
 *
 * - The first `perLinkAndDay` hosts of a (link, day) are admitted, and every later click from one of them counts under it. Any other
 *   host counts as [Referrer.Other] and is counted in `analytics.referrers.capped`. [Referrer.Direct] and [Referrer.Other] always pass.
 * - The hosts it admitted outlive the flushes, which empty the aggregator every few seconds. They live in a cache of at most `tracked`
 *   (link, day) pairs, so the memory is bounded. A pair it forgets, after a day without clicks or when the cache is full, starts again
 *   empty, and so does a restart: the database can hold more than `perLinkAndDay` hosts for a link and day, about that many per
 *   instance and per start.
 * - A host already admitted is found without a lock. Only a new host takes the pair's lock, so the cap is exact per instance.
 */
class ReferrerAdmission(
    private val perLinkAndDay: Int,
    tracked: Long,
    registry: MeterRegistry,
) {

    private data class LinkDay(val shortCode: String, val epochDay: Long)

    private val hosts: Cache<LinkDay, AdmittedHosts> = Caffeine.newBuilder()
        .maximumSize(tracked)
        .expireAfterAccess(Duration.ofDays(1))
        .build()

    private val capped = registry.counter("analytics.referrers.capped")

    fun admit(shortCode: String, epochDay: Long, referrer: Referrer): Referrer = when (referrer) {
        Referrer.Direct, Referrer.Other -> referrer

        is Referrer.Host ->
            if (hosts.get(LinkDay(shortCode, epochDay)) { AdmittedHosts(perLinkAndDay) }.admits(referrer.name)) {
                referrer
            } else {
                capped.increment()
                Referrer.Other
            }
    }

    private class AdmittedHosts(private val cap: Int) {

        private val names = ConcurrentHashMap.newKeySet<String>()

        fun admits(name: String): Boolean = name in names || admitNew(name)

        @Synchronized
        private fun admitNew(name: String): Boolean = name in names || (names.size < cap && names.add(name))
    }
}

package uy.ct.shortener.shortlink.internal

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import uy.ct.shortener.shortlink.LinkLookup
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import uy.ct.shortener.shortlink.StorageUnavailableException
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool

/**
 * In-memory [RedirectCache] in front of the database read of a redirect.
 *
 * - Keeps only active links. An unknown or disabled code is never cached.
 * - One cache per instance. The instance that disables a link evicts it at once. The others serve it until their entry
 *   expires ([RedirectCacheProperties.ttl]).
 * - Concurrent misses for a code run one load.
 * - Bounded by entry count. Metrics: the `cache.*` metrics of the cache named `shortlink.redirect`.
 *
 * When the database cannot be reached, a link whose entry has expired is still served from the last copy this instance read,
 * for up to [RedirectCacheProperties.staleIfError].
 *
 * - A code this instance never read still fails with `503`, and so does any failure that is not the database being unreachable.
 * - The copies live in a second cache, consulted only on that failure. Serving a stale copy is not a hit.
 * - A link the database then reports gone or disabled is dropped, as is one disabled here.
 * - Each redirect served this way increments `shortlink.redirect.cache.stale`.
 */
class CaffeineRedirectCache(
    properties: RedirectCacheProperties,
    registry: MeterRegistry,
    ticker: Ticker = Ticker.systemTicker(),
    executor: Executor = ForkJoinPool.commonPool(),
) : RedirectCache {

    private val cache: Cache<ShortCode, ShortLink> = Caffeine.newBuilder()
        .maximumSize(properties.maxEntries)
        .expireAfterWrite(properties.ttl)
        .ticker(ticker)
        .executor(executor)
        .recordStats()
        .build<ShortCode, ShortLink>()
        .also { CaffeineCacheMetrics.monitor(registry, it, CACHE_NAME) }

    // With `stale-if-error` at zero the size is 0 and nothing is kept. The expiry still has to be positive, so it falls back to the TTL.
    private val lastKnown: Cache<ShortCode, ShortLink> = Caffeine.newBuilder()
        .maximumSize(if (properties.staleIfError.isZero) 0 else properties.maxEntries)
        .expireAfterWrite(properties.staleIfError.takeUnless { it.isZero } ?: properties.ttl)
        .ticker(ticker)
        .executor(executor)
        .build()

    private val servedStale: Counter = Counter.builder("shortlink.redirect.cache.stale")
        .description("Redirects served from a link read earlier than the time to live because the database could not be reached")
        .register(registry)

    override fun find(shortCode: ShortCode, load: (ShortCode) -> LinkLookup): LinkLookup {
        var loadedHere = false
        var loaded: LinkLookup = LinkLookup.Missing
        val cached = try {
            nullableValues(cache).get(shortCode) {
                loadedHere = true
                loaded = load(it)
                remember(it, loaded)
                loaded.activeLink()
            }
        } catch (unavailable: StorageUnavailableException) {
            return lastKnown.getIfPresent(shortCode)?.let { LinkLookup.Found(it).also { servedStale.increment() } }
                ?: throw unavailable
        }
        return when {
            cached != null -> LinkLookup.Found(cached)
            loadedHere -> loaded
            // Another caller's load found nothing active. Only active links are stored, so ask again to tell a missing code from a disabled one.
            else -> load(shortCode)
        }
    }

    override fun evict(shortCode: ShortCode) {
        cache.invalidate(shortCode)
        lastKnown.invalidate(shortCode)
    }

    /** Keeps the copy a later outage can fall back to, and drops it once the database says the link is gone or disabled. */
    private fun remember(shortCode: ShortCode, lookup: LinkLookup) {
        val active = lookup.activeLink()
        if (active != null) lastKnown.put(shortCode, active) else lastKnown.invalidate(shortCode)
    }

    /** The link, if the lookup found one that still redirects. Caffeine takes a missing value as null. */
    private fun LinkLookup.activeLink(): ShortLink? = when (this) {
        is LinkLookup.Found -> link.takeIf(ShortLink::isActive)
        LinkLookup.Missing -> null
    }

    /**
     * Caffeine treats a loader that returns null as "keep nothing", which its Kotlin types cannot
     * express, hence the cast.
     */
    @Suppress("UNCHECKED_CAST")
    private fun nullableValues(cache: Cache<ShortCode, ShortLink>) = cache as Cache<ShortCode, ShortLink?>

    private companion object {
        const val CACHE_NAME = "shortlink.redirect"
    }
}

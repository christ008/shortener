package uy.ct.shortener.shortlink.internal

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import uy.ct.shortener.shortlink.LinkLookup
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool

/**
 * In-memory [RedirectCache] that takes the database read off the redirect path.
 *
 * - Keeps only active links. An unknown code is never cached, so a link works the moment it is
 *   created, and a disabled link is never served from here after its entry goes.
 * - One cache per instance. The instance that disables a link evicts it at once; the others serve
 *   it until their entry expires, so a takedown reaches every instance within the TTL.
 * - Concurrent misses for a code run one load, and the others wait for it.
 * - Bounded by entry count. Hits, misses, evictions and size are the `cache.*` metrics of the cache
 *   named `shortlink.redirect`.
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

    override fun find(shortCode: ShortCode, load: (ShortCode) -> LinkLookup): LinkLookup {
        var loadedHere = false
        var loaded: LinkLookup = LinkLookup.Missing
        val cached = nullableValues(cache).get(shortCode) {
            loadedHere = true
            loaded = load(it)
            (loaded as? LinkLookup.Found)?.link?.takeUnless(ShortLink::isDisabled)
        }
        return when {
            cached != null -> LinkLookup.Found(cached)
            loadedHere -> loaded
            else -> load(shortCode)
        }
    }

    override fun evict(shortCode: ShortCode) = cache.invalidate(shortCode)

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

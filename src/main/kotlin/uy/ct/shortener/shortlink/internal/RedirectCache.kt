package uy.ct.shortener.shortlink.internal

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool

/**
 * In-process cache of the links that redirects follow, which takes the database read off the hot
 * path. Only active links are kept: an unknown code is never cached, so a link works the moment it
 * is created, and neither is a disabled one, so a takedown is never served from here after its
 * entry goes. Each instance has its own cache. The instance that disables a link evicts it at once
 * with [evict]; the others serve it until their entry expires, so a takedown reaches every
 * instance within the configured TTL.
 *
 * Concurrent misses for one code run a single load while the others wait for its result. The
 * cache is bounded by entry count, and its hits, misses, evictions and size are exported as the
 * `cache.*` metrics of the cache named `shortlink.redirect`. Caffeine treats a load that returns
 * null as "nothing to keep", which its Kotlin types cannot express, hence the cast in [find].
 */
class RedirectCache(
    properties: RedirectCacheProperties,
    registry: MeterRegistry,
    ticker: Ticker = Ticker.systemTicker(),
    executor: Executor = ForkJoinPool.commonPool(),
) {

    private val cache: Cache<ShortCode, ShortLink>? = if (properties.enabled) {
        Caffeine.newBuilder()
            .maximumSize(properties.maxEntries)
            .expireAfterWrite(properties.ttl)
            .ticker(ticker)
            .executor(executor)
            .recordStats()
            .build<ShortCode, ShortLink>()
            .also { CaffeineCacheMetrics.monitor(registry, it, CACHE_NAME) }
    } else {
        null
    }

    fun find(shortCode: ShortCode, load: (ShortCode) -> ShortLink?): ShortLink? {
        val cache = cache ?: return load(shortCode)
        return loadingNullable(cache).get(shortCode) { load(it) }
    }

    fun evict(shortCode: ShortCode) {
        cache?.invalidate(shortCode)
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadingNullable(cache: Cache<ShortCode, ShortLink>) = cache as Cache<ShortCode, ShortLink?>

    private companion object {
        const val CACHE_NAME = "shortlink.redirect"
    }
}

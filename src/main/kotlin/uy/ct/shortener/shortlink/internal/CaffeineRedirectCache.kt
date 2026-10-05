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
 * In-memory [RedirectCache] that takes the database read off the redirect path.
 *
 * - Keeps only active links. An unknown code is never cached, so a link works the moment it is
 *   created, and a disabled link is never served from here after its entry goes.
 * - One cache per instance. The instance that disables a link evicts it at once; the others serve
 *   it until their entry expires, so a takedown reaches every instance within the TTL.
 * - Concurrent misses for a code run one load, and the others wait for it.
 * - Bounded by entry count. Hits, misses, evictions and size are the `cache.*` metrics of the cache
 *   named `shortlink.redirect`.
 *
 * When the database cannot be reached, a link whose entry has expired is still served from the last
 * copy this instance read, for up to [RedirectCacheProperties.staleIfError].
 * - Redirects of known links carry on through an outage. A code this instance never read still fails
 *   with the 503, and so does any failure that is not the database being unreachable.
 * - The copies live in a second cache, consulted only on that failure, so the main cache and its hit
 *   ratio count only what memory answered. Serving a stale copy is not a hit.
 * - A link the database then reports gone or disabled is dropped from it, as is one disabled here.
 * - The cost: a link disabled through another instance just before an outage keeps redirecting here
 *   until its copy expires, which is why the window is short.
 * - Each redirect served this way increments `shortlink.redirect.cache.stale`, which should be zero.
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
                (loaded as? LinkLookup.Found)?.link?.takeUnless(ShortLink::isDisabled)
            }
        } catch (unavailable: StorageUnavailableException) {
            return lastKnown.getIfPresent(shortCode)?.let { LinkLookup.Found(it).also { servedStale.increment() } }
                ?: throw unavailable
        }
        return when {
            cached != null -> LinkLookup.Found(cached)
            loadedHere -> loaded
            else -> load(shortCode)
        }
    }

    override fun evict(shortCode: ShortCode) {
        cache.invalidate(shortCode)
        lastKnown.invalidate(shortCode)
    }

    /** Keeps the copy a later outage can fall back to, and drops it once the database says the link is gone or disabled. */
    private fun remember(shortCode: ShortCode, lookup: LinkLookup) {
        val active = (lookup as? LinkLookup.Found)?.link?.takeUnless(ShortLink::isDisabled)
        if (active != null) lastKnown.put(shortCode, active) else lastKnown.invalidate(shortCode)
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

package uy.ct.shortener.shortlink.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The redirect cache on its own: what it keeps, for how long, how many, what it does when many
 * callers miss the same code at once, and what it reports. A manual ticker and a same-thread
 * executor make expiry and eviction exact.
 */
class RedirectCacheTest {

    private val ticker = FakeTicker()

    private val registry = SimpleMeterRegistry()

    private fun cacheOf(properties: RedirectCacheProperties = RedirectCacheProperties(ttl = Duration.ofSeconds(30))) =
        RedirectCache(properties, registry, ticker, Runnable::run)

    private fun link(code: String) = ShortLink(ShortCode(code), URI.create("https://example.com/$code"), "owner", Instant.EPOCH)

    private fun counter(result: String) =
        registry.get("cache.gets").tag("cache", "shortlink.redirect").tag("result", result).functionCounter().count()

    @Test
    fun `loads a link once and serves it from memory afterwards`() {
        val cache = cacheOf()
        val loads = AtomicInteger()

        repeat(3) { cache.find(ShortCode("aaaaaaa")) { loads.incrementAndGet(); link("aaaaaaa") } }

        assertThat(loads).hasValue(1)
    }

    @Test
    fun `keeps nothing when the load finds no link`() {
        val cache = cacheOf()
        val loads = AtomicInteger()

        repeat(3) { assertThat(cache.find(ShortCode("aaaaaaa")) { loads.incrementAndGet(); null }).isNull() }

        assertThat(loads).hasValue(3)
    }

    @Test
    fun `loads again after an eviction`() {
        val cache = cacheOf()
        val loads = AtomicInteger()
        val load = { _: ShortCode -> loads.incrementAndGet(); link("aaaaaaa") }
        cache.find(ShortCode("aaaaaaa"), load)

        cache.evict(ShortCode("aaaaaaa"))
        cache.find(ShortCode("aaaaaaa"), load)

        assertThat(loads).hasValue(2)
    }

    @Test
    fun `loads again once the time to live has passed, and not before`() {
        val cache = cacheOf()
        val loads = AtomicInteger()
        val load = { _: ShortCode -> loads.incrementAndGet(); link("aaaaaaa") }
        cache.find(ShortCode("aaaaaaa"), load)

        ticker.advance(Duration.ofSeconds(29))
        cache.find(ShortCode("aaaaaaa"), load)
        assertThat(loads).hasValue(1)
        ticker.advance(Duration.ofSeconds(2))
        cache.find(ShortCode("aaaaaaa"), load)

        assertThat(loads).hasValue(2)
    }

    @Test
    fun `never holds more entries than its limit`() {
        val cache = cacheOf(RedirectCacheProperties(maxEntries = 100))

        repeat(1_000) { i -> cache.find(ShortCode("code%04d".format(i))) { link("code%04d".format(i)) } }

        val size = registry.get("cache.size").tag("cache", "shortlink.redirect").gauge().value()
        assertThat(size).isLessThanOrEqualTo(100.0)
    }

    @Test
    fun `runs one load when many callers miss the same code at the same time`() {
        val cache = RedirectCache(RedirectCacheProperties(), registry)
        val callers = 16
        val loads = AtomicInteger()
        val start = CyclicBarrier(callers)
        val pool = Executors.newFixedThreadPool(callers)

        val results = try {
            List(callers) {
                pool.submit<ShortLink?> {
                    start.await(10, TimeUnit.SECONDS)
                    cache.find(ShortCode("aaaaaaa")) {
                        loads.incrementAndGet()
                        Thread.sleep(300)
                        link("aaaaaaa")
                    }
                }
            }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(loads).hasValue(1)
        assertThat(results).allSatisfy { assertThat(it?.shortCode).isEqualTo(ShortCode("aaaaaaa")) }
    }

    @Test
    fun `reports hits and misses`() {
        val cache = cacheOf()

        repeat(4) { cache.find(ShortCode("aaaaaaa")) { link("aaaaaaa") } }

        assertThat(counter("miss")).isEqualTo(1.0)
        assertThat(counter("hit")).isEqualTo(3.0)
    }

    @Test
    fun `passes every call to the load when switched off`() {
        val cache = cacheOf(RedirectCacheProperties(enabled = false))
        val loads = AtomicInteger()

        repeat(3) { cache.find(ShortCode("aaaaaaa")) { loads.incrementAndGet(); link("aaaaaaa") } }

        assertThat(loads).hasValue(3)
    }

    @Test
    fun `refuses settings that would make it useless or unbounded`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { RedirectCacheProperties(ttl = Duration.ZERO) }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { RedirectCacheProperties(maxEntries = 0) }
    }
}

package uy.ct.shortener.shortlink.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uy.ct.shortener.shortlink.Actor
import uy.ct.shortener.shortlink.LinkLookup
import uy.ct.shortener.shortlink.LinkStatus
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import uy.ct.shortener.shortlink.StorageUnavailableException
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/**
 * The redirect cache on its own: what it keeps, for how long, how many, what it does when many
 * callers miss the same code at once, and what it reports. A manual ticker and a same-thread
 * executor make expiry and eviction exact.
 */
class RedirectCacheTest {

    private val ticker = FakeTicker()

    private val registry = SimpleMeterRegistry()

    private fun cacheOf(properties: RedirectCacheProperties = RedirectCacheProperties(ttl = Duration.ofSeconds(30))) =
        CaffeineRedirectCache(properties, registry, ticker, Runnable::run)

    private fun link(code: String, status: LinkStatus = LinkStatus.Active) =
        LinkLookup.Found(ShortLink(ShortCode(code), URI.create("https://example.com/$code"), Actor.Client("owner"), Instant.EPOCH, status))

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

        repeat(3) { assertThat(cache.find(ShortCode("aaaaaaa")) { loads.incrementAndGet(); LinkLookup.Missing }).isEqualTo(LinkLookup.Missing) }

        assertThat(loads).hasValue(3)
    }

    @Test
    fun `returns a disabled link but does not keep it`() {
        val cache = cacheOf()
        val loads = AtomicInteger()
        val disabled = link("aaaaaaa", LinkStatus.Disabled(Instant.EPOCH, Actor.Client("owner")))

        val answers = List(3) { cache.find(ShortCode("aaaaaaa")) { loads.incrementAndGet(); disabled } }

        assertThat(answers).containsOnly(disabled)
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
        val cache = CaffeineRedirectCache(RedirectCacheProperties(), registry)
        val callers = 16
        val loads = AtomicInteger()
        val start = CyclicBarrier(callers)
        val pool = Executors.newFixedThreadPool(callers)

        val results = try {
            List(callers) {
                pool.submit<LinkLookup> {
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
        assertThat(results).allSatisfy { assertThat((it as LinkLookup.Found).link.shortCode).isEqualTo(ShortCode("aaaaaaa")) }
    }

    @Test
    fun `reports hits and misses`() {
        val cache = cacheOf()

        repeat(4) { cache.find(ShortCode("aaaaaaa")) { link("aaaaaaa") } }

        assertThat(counter("miss")).isEqualTo(1.0)
        assertThat(counter("hit")).isEqualTo(3.0)
    }

    @Test
    fun `passes every call to the load when it is the no-op cache`() {
        val cache = NoRedirectCache
        val loads = AtomicInteger()

        repeat(3) { cache.find(ShortCode("aaaaaaa")) { loads.incrementAndGet(); link("aaaaaaa") } }

        assertThat(loads).hasValue(3)
    }

    @Test
    fun `refuses settings that would make it useless or unbounded`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { RedirectCacheProperties(ttl = Duration.ZERO) }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { RedirectCacheProperties(maxEntries = 0) }
    }

    private val down = { _: ShortCode -> throw StorageUnavailableException(RuntimeException("the database is down")) }

    private fun staleCache(window: Duration = Duration.ofMinutes(5), maxEntries: Long = 100_000) =
        cacheOf(RedirectCacheProperties(ttl = Duration.ofSeconds(30), staleIfError = window, maxEntries = maxEntries))

    private fun stale() = registry.get("shortlink.redirect.cache.stale").counter().count()

    @Test
    fun `serves the link it last read when the database cannot be reached after the entry has expired, and counts it`() {
        val cache = staleCache()
        val known = cache.find(ShortCode("aaaaaaa")) { link("aaaaaaa") }
        ticker.advance(Duration.ofSeconds(31))

        val served = cache.find(ShortCode("aaaaaaa"), down)

        assertThat(served).isEqualTo(known)
        assertThat(stale()).isEqualTo(1.0)
    }

    @Test
    fun `stops serving it once the window has passed, and fails like any other lookup`() {
        val cache = staleCache(Duration.ofMinutes(5))
        cache.find(ShortCode("aaaaaaa")) { link("aaaaaaa") }
        ticker.advance(Duration.ofMinutes(5).plusSeconds(1))

        assertFailsWith<StorageUnavailableException> { cache.find(ShortCode("aaaaaaa"), down) }

        assertThat(stale()).isZero()
    }

    @Test
    fun `fails for a link it has never read, which is what a code nobody created gets`() {
        val cache = staleCache()
        cache.find(ShortCode("aaaaaaa")) { link("aaaaaaa") }

        assertFailsWith<StorageUnavailableException> { cache.find(ShortCode("bbbbbbb"), down) }
    }

    @Test
    fun `does not bring back a link the database has since said is gone or disabled`() {
        val cache = staleCache()
        cache.find(ShortCode("aaaaaaa")) { link("aaaaaaa") }
        ticker.advance(Duration.ofSeconds(31))
        assertThat(cache.find(ShortCode("aaaaaaa")) { LinkLookup.Missing }).isEqualTo(LinkLookup.Missing)
        ticker.advance(Duration.ofSeconds(31))

        assertFailsWith<StorageUnavailableException> { cache.find(ShortCode("aaaaaaa"), down) }
    }

    @Test
    fun `does not bring back a link that was disabled here, even for an outage`() {
        val cache = staleCache()
        cache.find(ShortCode("aaaaaaa")) { link("aaaaaaa") }
        cache.evict(ShortCode("aaaaaaa"))

        assertFailsWith<StorageUnavailableException> { cache.find(ShortCode("aaaaaaa"), down) }
    }

    @Test
    fun `does not hide a failure that is not the database being unreachable`() {
        val cache = staleCache()
        cache.find(ShortCode("aaaaaaa")) { link("aaaaaaa") }
        ticker.advance(Duration.ofSeconds(31))

        assertFailsWith<IllegalStateException> { cache.find(ShortCode("aaaaaaa")) { throw IllegalStateException("a bug") } }

        assertThat(stale()).isZero()
    }

    @Test
    fun `serves nothing stale when the window is zero`() {
        val cache = staleCache(Duration.ZERO)
        cache.find(ShortCode("aaaaaaa")) { link("aaaaaaa") }
        ticker.advance(Duration.ofSeconds(31))

        assertFailsWith<StorageUnavailableException> { cache.find(ShortCode("aaaaaaa"), down) }
    }

    @Test
    fun `does not count a redirect served stale as a hit, so the hit ratio stays the share answered from memory`() {
        val cache = staleCache()
        cache.find(ShortCode("aaaaaaa")) { link("aaaaaaa") }
        ticker.advance(Duration.ofSeconds(31))

        cache.find(ShortCode("aaaaaaa"), down)

        assertThat(counter("hit")).isZero()
        assertThat(counter("miss")).isEqualTo(2.0)
    }

    @Test
    fun `holds a stale copy for every entry it holds, within the same limit`() {
        val cache = staleCache(maxEntries = 2)
        val codes = listOf("aaaaaaa", "bbbbbbb", "ccccccc", "ddddddd")
        codes.forEach { code -> cache.find(ShortCode(code)) { link(code) } }
        ticker.advance(Duration.ofSeconds(31))

        val served = codes.count { code -> runCatching { cache.find(ShortCode(code), down) }.isSuccess }

        assertThat(served).isLessThanOrEqualTo(2)
    }

    @Test
    fun `refuses a stale window shorter than the time to live, which could never outlast an entry`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { RedirectCacheProperties(ttl = Duration.ofSeconds(30), staleIfError = Duration.ofSeconds(10)) }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { RedirectCacheProperties(staleIfError = Duration.ofSeconds(-1)) }
        assertThat(RedirectCacheProperties(staleIfError = Duration.ZERO).staleIfError).isZero()
        assertThat(RedirectCacheProperties(ttl = Duration.ofSeconds(30), staleIfError = Duration.ofSeconds(30)).staleIfError).isEqualTo(Duration.ofSeconds(30))
    }
}

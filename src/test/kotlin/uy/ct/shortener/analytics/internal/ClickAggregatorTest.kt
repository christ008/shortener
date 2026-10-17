package uy.ct.shortener.analytics.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import uy.ct.shortener.analytics.Language
import uy.ct.shortener.analytics.Referrer
import uy.ct.shortener.shortlink.ShortCode
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The counts an instance holds between flushes: one per link, hour and class, and for a visitor one for its referrer and one for its
 * language that day, added to atomically, bounded in number, and given back when they could not be written. The clock is moved by
 * hand, so the hour and the day of a click are known.
 *
 * - A bot's click makes one count, its hour's, so the tests of the cap and of concurrency use bots and know how many keys there are.
 */
class ClickAggregatorTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T10:15:00Z"))

    private val registry = SimpleMeterRegistry()

    private fun aggregator(maxEntries: Int = 100, hostsPerLinkAndDay: Int = 50) =
        ClickAggregator(maxEntries, clock, ReferrerAdmission(hostsPerLinkAndDay, 1_000, registry), registry)

    private fun ClickAggregator.record(
        code: String,
        device: DeviceClass = DeviceClass.BOT,
        referrer: Referrer = Referrer.Direct,
        language: Language = Language.Unknown,
    ) = record(Click(ShortCode(code), device, referrer, language))

    private fun recorded() = registry.counter("analytics.clicks.recorded").count()

    private fun dropped(reason: String) = registry.counter("analytics.clicks.dropped", "reason", reason).count()

    private fun ClickAggregator.counts() = drain(Int.MAX_VALUE).associate { it.key to it.clicks }

    private fun ClickAggregator.hourlyCounts(): Map<CountKey, Long> = counts().filterKeys { key ->
        when (key) {
            is CountKey.Hourly -> true
            is CountKey.ReferrerDaily, is CountKey.LanguageDaily -> false
        }
    }

    private fun key(code: String, hour: String, device: DeviceClass = DeviceClass.BOT) =
        CountKey.Hourly(code, Instant.parse(hour).epochSecond / 3600, device)

    private fun ClickAggregator.referrerCounts(): Map<Referrer, Long> = buildMap {
        drain(Int.MAX_VALUE).forEach { count ->
            when (val key = count.key) {
                is CountKey.ReferrerDaily -> put(key.referrer, count.clicks)
                is CountKey.Hourly, is CountKey.LanguageDaily -> Unit
            }
        }
    }

    private fun ClickAggregator.languageDays(): List<Long> = drain(Int.MAX_VALUE).mapNotNull { count ->
        when (val key = count.key) {
            is CountKey.LanguageDaily -> key.epochDay
            is CountKey.Hourly, is CountKey.ReferrerDaily -> null
        }
    }

    private fun day(at: String) = Instant.parse(at).epochSecond / 86_400

    @Test
    fun `adds the clicks of one link, hour and class together`() {
        val aggregator = aggregator()

        repeat(3) { aggregator.record("abc") }

        assertThat(aggregator.counts()).containsExactly(entry(key("abc", "2026-10-12T10:00:00Z"), 3L))
        assertThat(recorded()).isEqualTo(3.0)
    }

    @Test
    fun `keeps apart links, classes and hours`() {
        val aggregator = aggregator()

        aggregator.record("abc")
        aggregator.record("abd")
        aggregator.record("abc", DeviceClass.MOBILE)
        clock.advance(Duration.ofMinutes(45))
        aggregator.record("abc")

        assertThat(aggregator.hourlyCounts()).containsOnly(
            entry(key("abc", "2026-10-12T10:00:00Z"), 1L),
            entry(key("abd", "2026-10-12T10:00:00Z"), 1L),
            entry(key("abc", "2026-10-12T10:00:00Z", DeviceClass.MOBILE), 1L),
            entry(key("abc", "2026-10-12T11:00:00Z"), 1L),
        )
    }

    @Test
    fun `a visitor's click also counts under its referrer and its language that day`() {
        val aggregator = aggregator()

        repeat(2) { aggregator.record("abc", DeviceClass.DESKTOP, Referrer.Host("t.co"), Language.Code("es")) }
        aggregator.record("abc", DeviceClass.MOBILE, Referrer.Direct, Language.Code("en"))

        assertThat(aggregator.counts()).containsOnly(
            entry(key("abc", "2026-10-12T10:00:00Z", DeviceClass.DESKTOP), 2L),
            entry(key("abc", "2026-10-12T10:00:00Z", DeviceClass.MOBILE), 1L),
            entry(CountKey.ReferrerDaily("abc", day("2026-10-12T00:00:00Z"), Referrer.Host("t.co")), 2L),
            entry(CountKey.ReferrerDaily("abc", day("2026-10-12T00:00:00Z"), Referrer.Direct), 1L),
            entry(CountKey.LanguageDaily("abc", day("2026-10-12T00:00:00Z"), Language.Code("es")), 2L),
            entry(CountKey.LanguageDaily("abc", day("2026-10-12T00:00:00Z"), Language.Code("en")), 1L),
        )
        assertThat(recorded()).isEqualTo(3.0)
    }

    @Test
    fun `a bot's click counts in its hour only, whatever its headers say`() {
        val aggregator = aggregator()

        aggregator.record("abc", DeviceClass.BOT, Referrer.Host("t.co"), Language.Code("es"))

        assertThat(aggregator.counts().keys).containsExactly(key("abc", "2026-10-12T10:00:00Z", DeviceClass.BOT))
    }

    @Test
    fun `a day is a UTC day, apart from the hours in it`() {
        val aggregator = aggregator()
        clock.advance(Duration.ofHours(13).plusMinutes(44))

        aggregator.record("abc", DeviceClass.DESKTOP, Referrer.Direct, Language.Code("es"))
        clock.advance(Duration.ofMinutes(2))
        aggregator.record("abc", DeviceClass.DESKTOP, Referrer.Direct, Language.Code("es"))

        assertThat(aggregator.languageDays()).containsExactlyInAnyOrder(day("2026-10-12T00:00:00Z"), day("2026-10-13T00:00:00Z"))
    }

    @Test
    fun `counts a referrer host past the ones admitted for the link that day as other`() {
        val aggregator = aggregator(hostsPerLinkAndDay = 2)

        listOf("a.example", "b.example", "c.example", "a.example").forEach {
            aggregator.record("abc", DeviceClass.DESKTOP, Referrer.Host(it), Language.Unknown)
        }

        assertThat(aggregator.referrerCounts()).containsOnly(
            entry(Referrer.Host("a.example"), 2L),
            entry(Referrer.Host("b.example"), 1L),
            entry(Referrer.Other, 1L),
        )
        assertThat(registry.counter("analytics.referrers.capped").count()).isEqualTo(1.0)
    }

    @Test
    fun `a visitor's count that needs a new key past the cap is dropped, each on its own`() {
        val aggregator = aggregator(maxEntries = 2)

        aggregator.record("abc", DeviceClass.DESKTOP, Referrer.Direct, Language.Unknown)

        assertThat(aggregator.size).isEqualTo(2)
        assertThat(recorded()).isEqualTo(1.0)
        assertThat(dropped("full")).isEqualTo(1.0)
    }

    @Test
    fun `the hour starts on the hour, in UTC`() {
        val key = key("abc", "2026-10-12T10:59:59Z")

        assertThat(key.epochSecond).isEqualTo(Instant.parse("2026-10-12T10:00:00Z").epochSecond)
        assertThat(key.epochHour).isEqualTo(Instant.parse("2026-10-12T10:00:00Z").epochSecond / 3600)
    }

    @Test
    fun `drain takes counts out, up to the limit`() {
        val aggregator = aggregator()
        listOf("aaa", "bbb", "ccc").forEach { aggregator.record(it) }

        val first = aggregator.drain(2)

        assertThat(first).hasSize(2)
        assertThat(aggregator.size).isEqualTo(1)
        assertThat(aggregator.drain(2)).hasSize(1)
        assertThat(aggregator.drain(2)).isEmpty()
    }

    @Test
    fun `holds at most the cap, drops the click that would need another entry and says so`() {
        val aggregator = aggregator(maxEntries = 2)

        aggregator.record("aaa")
        aggregator.record("bbb")
        aggregator.record("ccc")

        assertThat(aggregator.size).isEqualTo(2)
        assertThat(recorded()).isEqualTo(2.0)
        assertThat(dropped("full")).isEqualTo(1.0)
    }

    @Test
    fun `a key already held keeps counting at the cap`() {
        val aggregator = aggregator(maxEntries = 1)

        repeat(5) { aggregator.record("aaa") }

        assertThat(aggregator.counts().values).containsExactly(5L)
        assertThat(dropped("full")).isZero()
    }

    @Test
    fun `a drained key makes room`() {
        val aggregator = aggregator(maxEntries = 1)
        aggregator.record("aaa")
        aggregator.drain(1)

        aggregator.record("bbb")

        assertThat(aggregator.size).isEqualTo(1)
        assertThat(dropped("full")).isZero()
    }

    @Test
    fun `putBack adds a batch to what has been counted since`() {
        val aggregator = aggregator()
        repeat(2) { aggregator.record("abc") }
        val batch = aggregator.drain(10)
        aggregator.record("abc")

        aggregator.putBack(batch)

        assertThat(aggregator.counts().values).containsExactly(3L)
        assertThat(dropped("storage")).isZero()
    }

    @Test
    fun `putBack drops, and counts the clicks of, what no longer fits`() {
        val aggregator = aggregator(maxEntries = 1)
        repeat(4) { aggregator.record("aaa") }
        val batch = aggregator.drain(10)
        aggregator.record("bbb")

        aggregator.putBack(batch)

        assertThat(aggregator.size).isEqualTo(1)
        assertThat(dropped("storage")).isEqualTo(4.0)
    }

    @Test
    fun `loses no click when many threads record while another drains`() {
        val aggregator = aggregator()
        val threads = 8
        val perThread = 20_000
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val recording = (0 until threads).map {
            pool.submit {
                start.await()
                repeat(perThread) { n -> aggregator.record("code${n % 50}") }
            }
        }

        start.countDown()
        var drained = 0L
        while (recording.any { !it.isDone }) drained += aggregator.drain(7).sumOf { it.clicks }
        recording.forEach { it.get(30, TimeUnit.SECONDS) }
        drained += aggregator.drain(Int.MAX_VALUE).sumOf { it.clicks }
        pool.shutdown()

        assertThat(dropped("full")).describedAs("clicks dropped as if the aggregator were full: it never holds more than 50 keys").isZero()
        assertThat(recorded()).isEqualTo((threads * perThread).toDouble())
        assertThat(drained).isEqualTo(threads.toLong() * perThread)
    }

    @Test
    fun `a drain never returns a key twice, though the walk can meet a key that was counted again`() {
        val aggregator = aggregator()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val recording = (0 until 8).map {
            pool.submit {
                start.await()
                repeat(20_000) { n -> aggregator.record("code${n % 50}") }
            }
        }

        start.countDown()
        val batches = mutableListOf<List<ClickCount>>()
        while (recording.any { !it.isDone }) batches += aggregator.drain(Int.MAX_VALUE)
        recording.forEach { it.get(30, TimeUnit.SECONDS) }
        batches += aggregator.drain(Int.MAX_VALUE)
        pool.shutdown()

        batches.forEach { batch -> assertThat(batch.map { it.key }).doesNotHaveDuplicates() }
        assertThat(batches.sumOf { batch -> batch.sumOf { it.clicks } }).isEqualTo(160_000L)
    }

    @Test
    fun `holds exactly the cap when many threads bring new keys at once`() {
        val aggregator = aggregator(maxEntries = 10)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val recording = (0 until 8).map { thread ->
            pool.submit {
                start.await()
                repeat(500) { n -> aggregator.record("t$thread-$n") }
            }
        }

        start.countDown()
        recording.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertThat(aggregator.size).isEqualTo(10)
        assertThat(aggregator.drain(Int.MAX_VALUE)).hasSize(10)
        assertThat(recorded()).isEqualTo(10.0)
        assertThat(dropped("full")).isEqualTo(3_990.0)
        assertThat(aggregator.size).isZero()
    }

    @Test
    fun `reports the entries it holds`() {
        val aggregator = aggregator()
        aggregator.record("aaa")
        aggregator.record("bbb")

        assertThat(registry.get("analytics.aggregator.entries").gauge().value()).isEqualTo(2.0)
    }
}

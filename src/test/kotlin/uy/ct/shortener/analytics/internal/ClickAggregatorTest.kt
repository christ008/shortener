package uy.ct.shortener.analytics.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import uy.ct.shortener.shortlink.ShortCode
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The counts an instance holds between flushes: one per link, hour and class, added to atomically, bounded in number, and
 * given back when they could not be written. The clock is moved by hand, so the hour of a click is known.
 */
class ClickAggregatorTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T10:15:00Z"))

    private val registry = SimpleMeterRegistry()

    private fun aggregator(maxEntries: Int = 100) = ClickAggregator(maxEntries, clock, registry)

    private fun ClickAggregator.record(code: String, device: DeviceClass = DeviceClass.DESKTOP) = record(ShortCode(code), device)

    private fun recorded() = registry.counter("analytics.clicks.recorded").count()

    private fun dropped(reason: String) = registry.counter("analytics.clicks.dropped", "reason", reason).count()

    private fun ClickAggregator.counts() = drain(Int.MAX_VALUE).associate { it.key to it.clicks }

    private fun key(code: String, hour: String, device: DeviceClass = DeviceClass.DESKTOP) =
        ClickKey(code, Instant.parse(hour).epochSecond / 3600, device)

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

        assertThat(aggregator.counts()).containsOnly(
            entry(key("abc", "2026-10-12T10:00:00Z"), 1L),
            entry(key("abd", "2026-10-12T10:00:00Z"), 1L),
            entry(key("abc", "2026-10-12T10:00:00Z", DeviceClass.MOBILE), 1L),
            entry(key("abc", "2026-10-12T11:00:00Z"), 1L),
        )
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

        assertThat(drained).isEqualTo(threads.toLong() * perThread)
        assertThat(recorded()).isEqualTo((threads * perThread).toDouble())
    }

    @Test
    fun `reports the entries it holds`() {
        val aggregator = aggregator()
        aggregator.record("aaa")
        aggregator.record("bbb")

        assertThat(registry.get("analytics.aggregator.entries").gauge().value()).isEqualTo(2.0)
    }
}

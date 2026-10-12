package uy.ct.shortener.analytics.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import uy.ct.shortener.LogCapture
import uy.ct.shortener.shortlink.ShortCode
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What a flush does with the counts of an aggregator, against a store that can be made to fail: it writes everything in batches,
 * keeps what it could not write for the next flush, says so once, and never reaches the caller as an error.
 */
class ClickFlusherTest {

    private val registry = SimpleMeterRegistry()

    private val aggregator = ClickAggregator(100, MutableClock(Instant.parse("2026-10-12T10:15:00Z")), registry)

    private val store = RecordingStore()

    private val logger = LoggerFactory.getLogger(ClickFlusher::class.java)

    private fun flusher(batchSize: Int = 500) = ClickFlusher(aggregator, store, batchSize, Duration.ofSeconds(10), registry, logger)

    private fun record(code: String, times: Int = 1) = repeat(times) { aggregator.record(ShortCode(code), DeviceClass.DESKTOP) }

    private fun flushes(outcome: String) = registry.timer("analytics.flush", "outcome", outcome).count()

    @Test
    fun `writes what the aggregator holds and empties it`() {
        record("aaa", 2)
        record("bbb")

        flusher().flush()

        assertThat(store.written.flatten().associate { it.key.shortCode to it.clicks }).containsOnly(
            entry("aaa", 2L),
            entry("bbb", 1L),
        )
        assertThat(aggregator.size).isZero()
        assertThat(flushes("success")).isEqualTo(1L)
    }

    @Test
    fun `writes in batches of the configured size`() {
        listOf("aaa", "bbb", "ccc", "ddd", "eee").forEach { record(it) }

        flusher(batchSize = 2).flush()

        assertThat(store.written.map { it.size }).containsExactly(2, 2, 1)
    }

    @Test
    fun `writes nothing when there is nothing`() {
        flusher().flush()

        assertThat(store.written).isEmpty()
        assertThat(flushes("success")).isZero()
    }

    @Test
    fun `keeps the counts of a batch it could not write, and the next flush writes them`() {
        record("aaa", 3)
        store.failure = DataAccessResourceFailureException("connection refused")
        val flusher = flusher()

        flusher.flush()

        assertThat(aggregator.size).isEqualTo(1)
        assertThat(flushes("failure")).isEqualTo(1L)

        store.failure = null
        record("aaa")
        flusher.flush()

        assertThat(store.written.single().single().clicks).isEqualTo(4L)
        assertThat(aggregator.size).isZero()
    }

    @Test
    fun `stops at the first failure instead of trying every batch`() {
        listOf("aaa", "bbb", "ccc").forEach { record(it) }
        store.failure = DataAccessResourceFailureException("down")

        flusher(batchSize = 1).flush()

        assertThat(store.attempts).isEqualTo(1)
        assertThat(aggregator.size).isEqualTo(3)
    }

    @Test
    fun `says once that counts are not being written, and when they are again, without the cause's message`() {
        LogCapture(ClickFlusher::class.java.name).use { log ->
            record("aaa")
            store.failure = DataAccessResourceFailureException("password=hunter2")
            val flusher = flusher()

            flusher.flush()
            flusher.flush()
            store.failure = null
            flusher.flush()

            assertThat(log.events.map { it.message }).hasSize(2)
            assertThat(log.events.first().message).contains("DataAccessResourceFailureException").doesNotContain("hunter2")
            assertThat(log.events.last().message).contains("again")
        }
    }

    @Test
    fun `stopping writes what is left, once`() {
        val flusher = flusher()
        flusher.start()
        record("aaa", 5)

        flusher.stop()

        assertThat(store.written.single().single().clicks).isEqualTo(5L)
        assertThat(flusher.isRunning).isFalse()
    }

    @Test
    fun `stopping a flusher that never started still writes what is left`() {
        record("aaa", 2)

        flusher().stop()

        assertThat(store.written.single().single().clicks).isEqualTo(2L)
    }

    @Test
    fun `writes on its own timer, without being asked`() {
        val flusher = ClickFlusher(aggregator, store, 500, Duration.ofMillis(20), registry, logger)
        flusher.start()
        try {
            assertThat(flusher.isRunning).isTrue()
            record("aaa", 4)

            val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
            while (store.written.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)

            assertThat(store.written.single().single().clicks).isEqualTo(4L)
        } finally {
            flusher.stop()
        }
    }

    @Test
    fun `stops after the web server and before the pool closes`() {
        assertThat(flusher().phase).isLessThan(Int.MAX_VALUE - 1)
    }

    private class RecordingStore : ClickStore {

        val written = CopyOnWriteArrayList<List<ClickCount>>()

        @Volatile
        var attempts = 0

        @Volatile
        var failure: RuntimeException? = null

        override fun add(counts: List<ClickCount>) {
            attempts++
            failure?.let { throw it }
            written += counts
        }
    }
}

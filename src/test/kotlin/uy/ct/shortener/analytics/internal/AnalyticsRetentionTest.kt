package uy.ct.shortener.analytics.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import uy.ct.shortener.LogCapture
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * How a run of retention goes, against a store that answers steps from a script: it keeps stepping while a step makes progress,
 * stops when there is nothing left or another instance holds the lock, never runs away, and never lets a failure out.
 */
class AnalyticsRetentionTest {

    private val registry = SimpleMeterRegistry()

    private val logger = LoggerFactory.getLogger(AnalyticsRetention::class.java)

    private class ScriptedStore(vararg steps: RetentionStep) : RetentionStore {

        private val script = ArrayDeque(steps.toList())

        var calls = 0

        var failure: RuntimeException? = null

        override fun step(): RetentionStep {
            calls++
            failure?.let { throw it }
            return script.removeFirstOrNull() ?: RetentionStep.Done
        }
    }

    private fun retention(store: RetentionStore, interval: Duration = Duration.ofHours(24), delay: Duration = Duration.ofHours(1)) =
        AnalyticsRetention(store, delay, interval, registry, logger)

    private fun runs(outcome: String) = registry.timer("analytics.retention", "outcome", outcome).count()

    @Test
    fun `steps until there is nothing left, and counts what it folded`() {
        val store = ScriptedStore(RetentionStep.Progress(48, 0), RetentionStep.Progress(24, 3), RetentionStep.Done)

        retention(store).run()

        assertThat(store.calls).isEqualTo(3)
        assertThat(registry.counter("analytics.retention.hours.folded").count()).isEqualTo(72.0)
        assertThat(registry.counter("analytics.retention.days.folded").count()).isEqualTo(3.0)
        assertThat(runs("done")).isEqualTo(1L)
    }

    @Test
    fun `stops at once when another instance is doing the work`() {
        val store = ScriptedStore(RetentionStep.Busy, RetentionStep.Progress(1, 1))

        retention(store).run()

        assertThat(store.calls).isEqualTo(1)
        assertThat(runs("busy")).isEqualTo(1L)
    }

    @Test
    fun `a run is bounded, and the next one carries on`() {
        val store = object : RetentionStore {
            var calls = 0

            override fun step(): RetentionStep {
                calls++
                return RetentionStep.Progress(1, 0)
            }
        }

        retention(store).run()

        assertThat(store.calls).isEqualTo(1_000)
        assertThat(runs("done")).isEqualTo(1L)
    }

    @Test
    fun `a failure is logged with its type and counted, and never raised`() {
        LogCapture(AnalyticsRetention::class.java.name).use { log ->
            val store = ScriptedStore().apply { failure = DataAccessResourceFailureException("password=hunter2") }

            retention(store).run()

            assertThat(runs("failure")).isEqualTo(1L)
            assertThat(log.events.single().message).contains("DataAccessResourceFailureException").doesNotContain("hunter2")
        }
    }

    @Test
    fun `says what it did when it did something, and nothing when it did not`() {
        LogCapture(AnalyticsRetention::class.java.name).use { log ->
            retention(ScriptedStore(RetentionStep.Done)).run()
            retention(ScriptedStore(RetentionStep.Progress(24, 2))).run()
            retention(ScriptedStore(RetentionStep.Progress(0, 5))).run()

            assertThat(log.events.map { it.message }).containsExactly(
                "Analytics retention folded 24 hourly rows into days and 2 days into months",
                "Analytics retention folded 0 hourly rows into days and 5 days into months",
            )
        }
    }

    @Test
    fun `runs on its own timer once started, and stops`() {
        val calls = CopyOnWriteArrayList<Long>()
        val store = RetentionStore {
            calls += System.nanoTime()
            RetentionStep.Done
        }
        val retention = retention(store, interval = Duration.ofMillis(20), delay = Duration.ZERO)

        retention.start()
        try {
            assertThat(retention.isRunning).isTrue()
            val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
            while (calls.size < 2 && System.nanoTime() < deadline) Thread.sleep(10)
            assertThat(calls.size).isGreaterThanOrEqualTo(2)
        } finally {
            retention.stop()
        }
        assertThat(retention.isRunning).isFalse()
    }
}

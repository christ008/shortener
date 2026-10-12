package uy.ct.shortener.analytics.internal

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Writes the counts of a [ClickAggregator] to a [ClickStore], on a timer, and once more when the application stops.
 *
 * - [flush] drains the aggregator in batches of `batchSize`. A batch that cannot be written goes back, up to the aggregator's bound,
 *   and the flush ends: the next one tries again.
 * - Failure is a failed flush, never an error to anyone else: readiness does not include it (ADR 0012)
 *   and a redirect does not wait for it.
 * - The first failure of a run is logged with the exception's type, never the statement or its values, and so is the recovery.
 * - Stops after the web server, so the last redirects of a shutdown are counted, and before the pool closes.
 * - Metrics: `analytics.flush` as a timer tagged `outcome` (`success` or `failure`).
 */
class ClickFlusher(
    private val aggregator: ClickAggregator,
    private val store: ClickStore,
    private val batchSize: Int,
    private val interval: Duration,
    private val registry: MeterRegistry,
    private val logger: Logger = LoggerFactory.getLogger(ClickFlusher::class.java),
) : SmartLifecycle {

    private var scheduler: ScheduledExecutorService? = null

    @Volatile
    private var failing = false

    fun flush() {
        repeat(aggregator.size / batchSize + 1) {
            val batch = aggregator.drain(batchSize)
            if (batch.isEmpty()) return
            if (!write(batch)) return
        }
    }

    private fun write(batch: List<ClickCount>): Boolean {
        val timer = Timer.start(registry)
        return try {
            store.add(batch)
            timer.stop(registry.timer("analytics.flush", "outcome", "success"))
            if (failing) logger.info("Click counts are being written again")
            failing = false
            true
        } catch (failure: RuntimeException) {
            timer.stop(registry.timer("analytics.flush", "outcome", "failure"))
            aggregator.putBack(batch)
            if (!failing) logger.warn("Click counts could not be written, they are kept in memory up to the cap: {}", failure.javaClass.simpleName)
            failing = true
            false
        }
    }

    override fun start() {
        scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "analytics-flush").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay(::flush, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS) }
    }

    override fun stop() {
        scheduler?.let {
            it.shutdown()
            it.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        scheduler = null
        flush()
    }

    override fun isRunning(): Boolean = scheduler != null

    override fun getPhase(): Int = SmartLifecycle.DEFAULT_PHASE - 1024

    private companion object {
        const val STOP_TIMEOUT_SECONDS = 5L
    }
}

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
 * Keeps the click counts within their retention, on a timer: hours for 90 days, folded into days, days for 730, folded into months,
 * kept for ever (ADR 0043).
 *
 * - [run] calls the [RetentionStore] step after step until a step finds nothing to do, another instance is doing it, or [MAX_STEPS]
 *   steps have run: a step does at most one day, so a run catches up on many days at once, and the bound keeps a run finite.
 * - It runs a little after the application starts and then every `interval`. Every instance runs it; the database lets one at a time
 *   do the work, and a second run in a day finds nothing.
 * - A failure is logged with its type and counted, never raised: the next run tries again. Readiness does not depend on it.
 * - Metrics: `analytics.retention` as a timer tagged `outcome` (`done`, `busy` or `failure`), `analytics.retention.hours.folded` and
 *   `analytics.retention.days.folded`.
 */
class AnalyticsRetention(
    private val store: RetentionStore,
    private val initialDelay: Duration,
    private val interval: Duration,
    private val registry: MeterRegistry,
    private val logger: Logger = LoggerFactory.getLogger(AnalyticsRetention::class.java),
) : SmartLifecycle {

    private val hoursFolded = registry.counter("analytics.retention.hours.folded")

    private val daysFolded = registry.counter("analytics.retention.days.folded")

    private var scheduler: ScheduledExecutorService? = null

    fun run() {
        val timer = Timer.start(registry)
        var folded = 0L
        var days = 0L
        val outcome = try {
            var steps = 1
            var step = store.step()
            while (step is RetentionStep.Progress) {
                folded += step.hoursFolded
                days += step.daysFolded
                step = if (steps++ < MAX_STEPS) store.step() else RetentionStep.Done
            }
            when (step) {
                RetentionStep.Busy -> "busy"
                RetentionStep.Done, is RetentionStep.Progress -> "done"
            }
        } catch (failure: RuntimeException) {
            logger.warn("Analytics retention failed, the next run tries again: {}", failure.javaClass.simpleName)
            "failure"
        }
        timer.stop(registry.timer("analytics.retention", "outcome", outcome))
        hoursFolded.increment(folded.toDouble())
        daysFolded.increment(days.toDouble())
        if (folded > 0 || days > 0) logger.info("Analytics retention folded {} hourly rows into days and {} days into months", folded, days)
    }

    override fun start() {
        scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "analytics-retention").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay(::run, initialDelay.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS) }
    }

    override fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    override fun isRunning(): Boolean = scheduler != null

    private companion object {
        const val MAX_STEPS = 1_000
    }
}

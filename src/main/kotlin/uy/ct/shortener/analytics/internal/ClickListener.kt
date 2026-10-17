package uy.ct.shortener.analytics.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import uy.ct.shortener.analytics.Language
import uy.ct.shortener.analytics.Referrer
import uy.ct.shortener.shortlink.LinkFollowed

/**
 * Turns a [LinkFollowed] into a counted click.
 *
 * - It runs in the redirect's thread, so it classifies the `User-Agent`, reads the referrer's host and the language, and records,
 *   and nothing else. The headers themselves are never kept.
 * - Counting must never fail a redirect: a failure is logged once with its type and counted in `analytics.clicks.dropped{reason=error}`.
 * - The flag that keeps that log to one line is written only when it changes, on success and on failure, so redirects on many cores
 *   do not contend for it.
 */
@Component
class ClickListener(
    private val recorder: ClickRecorder,
    registry: MeterRegistry,
    private val logger: Logger,
) {

    @Autowired
    constructor(recorder: ClickRecorder, registry: MeterRegistry) : this(recorder, registry, LoggerFactory.getLogger(ClickListener::class.java))

    private val dropped = registry.counter("analytics.clicks.dropped", "reason", "error")

    @Volatile
    private var failing = false

    @EventListener
    fun on(event: LinkFollowed) {
        try {
            recorder.record(
                Click(event.shortCode, DeviceClassifier.classify(event.userAgent), Referrer.of(event.referer), Language.of(event.acceptLanguage)),
            )
            if (failing) failing = false
        } catch (failure: RuntimeException) {
            dropped.increment()
            if (!failing) {
                failing = true
                logger.warn("A click could not be counted: {}", failure.javaClass.simpleName)
            }
        }
    }
}

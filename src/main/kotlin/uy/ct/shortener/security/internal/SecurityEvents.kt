package uy.ct.shortener.security.internal

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * What the security filter chain reports about requests it turned away, as a log line and a counter.
 *
 * - One event per `401` ([unauthenticated]), `403` ([forbidden]) and `429` ([rateLimited]), on the logger [LOGGER_NAME].
 * - A line has the type, the reason (the OAuth error code), the scheme, the method and path, the address and the client when
 *   there is one. It never has a token, a proof, a query string, a header value or an exception message.
 * - Every event increments `shortener.security.events`, tagged with the type only.
 * - A `429` is logged once per key and minute and counted every time. `401` and `403` are logged every time.
 */
class SecurityEvents(
    private val meters: MeterRegistry,
    private val logger: Logger = LoggerFactory.getLogger(LOGGER_NAME),
    ticker: Ticker = Ticker.systemTicker(),
) {

    private val limitedRecently = Caffeine.newBuilder()
        .maximumSize(MAX_REMEMBERED_KEYS)
        .expireAfterWrite(Duration.ofMinutes(1))
        .ticker(ticker)
        .build<String, Boolean>()

    fun unauthenticated(request: HttpServletRequest, reason: String) {
        count(UNAUTHENTICATED)
        logger.atWarn().describing(UNAUTHENTICATED, request, reason).log("Authentication failed")
    }

    fun forbidden(request: HttpServletRequest, client: String?, reason: String) {
        count(FORBIDDEN)
        logger.atWarn().describing(FORBIDDEN, request, reason).addKeyValue("owner", client.orEmpty()).log("Access denied")
    }

    fun rateLimited(request: HttpServletRequest, limit: String, key: String) {
        count(RATE_LIMITED)
        if (limitedRecently.asMap().putIfAbsent("$limit:$key", true) != null) return
        logger.atWarn().describing(RATE_LIMITED, request, "limit_$limit").addKeyValue("limited.key", key).log("Rate limit exceeded")
    }

    private fun count(type: String) = meters.counter(METRIC, "type", type).increment()

    private fun org.slf4j.spi.LoggingEventBuilder.describing(type: String, request: HttpServletRequest, reason: String) =
        addKeyValue("event.category", "security")
            .addKeyValue("event.action", type)
            .addKeyValue("event.reason", reason)
            .addKeyValue("auth.scheme", AuthScheme.of(request).name.lowercase())
            .addKeyValue("http.request.method", request.method)
            .addKeyValue("url.path", request.requestURI.take(MAX_PATH))
            .addKeyValue("client.ip", request.remoteAddr)

    companion object {
        const val LOGGER_NAME = "uy.ct.shortener.security.events"
        const val METRIC = "shortener.security.events"
        const val UNAUTHENTICATED = "unauthenticated"
        const val FORBIDDEN = "forbidden"
        const val RATE_LIMITED = "rate_limited"
        private const val MAX_REMEMBERED_KEYS = 10_000L
        private const val MAX_PATH = 200
    }
}

package uy.ct.shortener.security.internal

import com.github.benmanes.caffeine.cache.Ticker
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import uy.ct.shortener.LogCapture
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * What a turned-away request leaves behind: one line with enough to investigate and nothing that could be replayed, and a
 * counter with three series.
 */
class SecurityEventsTest {

    private val meters = SimpleMeterRegistry()

    private val clock = AtomicLong()

    private val events = SecurityEvents(meters, ticker = Ticker { clock.get() })

    private fun request(authorization: String? = null) = MockHttpServletRequest("POST", "/api/short-links").apply {
        remoteAddr = "203.0.113.9"
        queryString = "access_token=SECRET-IN-QUERY"
        authorization?.let { addHeader("Authorization", it) }
    }

    private fun count(type: String) = meters.counter(SecurityEvents.METRIC, "type", type).count()

    @Test
    fun `an authentication failure says why, from where and with which scheme, and carries no credential`() {
        LogCapture(SecurityEvents.LOGGER_NAME).use { log ->
            events.unauthenticated(request("DPoP eyJhbGciOiJFUzI1NiJ9.SECRET-TOKEN.signature"), "invalid_token")

            val line = log.events.single()
            assertThat(line.fields).containsEntry("event.action", "unauthenticated")
                .containsEntry("event.reason", "invalid_token")
                .containsEntry("auth.scheme", "dpop")
                .containsEntry("http.request.method", "POST")
                .containsEntry("url.path", "/api/short-links")
                .containsEntry("client.ip", "203.0.113.9")
            assertThat(line.toString()).doesNotContain("SECRET")
        }
        assertThat(count("unauthenticated")).isEqualTo(1.0)
    }

    @Test
    fun `a denied call names the client that made it`() {
        LogCapture(SecurityEvents.LOGGER_NAME).use { log ->
            events.forbidden(request(), "user:abc", "insufficient_scope")

            assertThat(log.events.single().fields).containsEntry("owner", "user:abc").containsEntry("event.action", "forbidden")
        }
        assertThat(count("forbidden")).isEqualTo(1.0)
    }

    @Test
    fun `a limited client is counted every time and logged once a minute`() {
        LogCapture(SecurityEvents.LOGGER_NAME).use { log ->
            repeat(50) { events.rateLimited(request(), "ip", "203.0.113.9") }
            events.rateLimited(request(), "ip", "198.51.100.4")

            assertThat(log.events).hasSize(2)
            clock.addAndGet(Duration.ofSeconds(61).toNanos())
            events.rateLimited(request(), "ip", "203.0.113.9")
            assertThat(log.events).hasSize(3)
            assertThat(log.events.first().fields).containsEntry("event.reason", "limit_ip").containsEntry("limited.key", "203.0.113.9")
        }
        assertThat(count("rate_limited")).isEqualTo(52.0)
    }

    @Test
    fun `every line is a warning in the security category`() {
        LogCapture(SecurityEvents.LOGGER_NAME).use { log ->
            events.unauthenticated(request(), "missing_credentials")

            assertThat(log.events.single().level.toString()).isEqualTo("WARN")
            assertThat(log.events.single().fields).containsEntry("event.category", "security")
        }
    }
}

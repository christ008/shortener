package uy.ct.shortener.security.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `shortener.security.*` settings. [apiKeys] maps a client name to the hex SHA-256 of its API
 * key; the name is recorded as the creator of the links it makes. [RateLimit] holds the
 * token-bucket limits, which are per pod: [RateLimit.perClient] applies to all requests from one
 * client IP and [RateLimit.perKey] to requests from one API key. A [Limit] holds [Limit.capacity]
 * requests, fully refilled over [Limit.period], one minute unless set.
 */
@ConfigurationProperties("shortener.security")
data class SecurityProperties(
    val apiKeys: Map<String, String> = emptyMap(),
    val rateLimit: RateLimit = RateLimit(),
) {

    data class RateLimit(
        val perClient: Limit = Limit(capacity = 300),
        val perKey: Limit = Limit(capacity = 60),
    )

    data class Limit(val capacity: Long, val period: Duration = Duration.ofMinutes(1))
}

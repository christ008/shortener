package uy.ct.shortener.security.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `shortener.security.*` settings. A client is identified by the [clientIdClaim] of its access
 * token (`azp` by default), and that name is recorded as the owner of the links it creates. [RateLimit] holds the
 * token-bucket limits, which are per pod: [RateLimit.perIp] applies to all requests from one
 * client IP and [RateLimit.perClient] to requests from one authenticated client. A [Limit] holds
 * [Limit.capacity] requests, fully refilled over [Limit.period], one minute unless set.
 */
@ConfigurationProperties("shortener.security")
data class SecurityProperties(
    val clientIdClaim: String = "azp",
    val rateLimit: RateLimit = RateLimit(),
) {

    data class RateLimit(
        val perIp: Limit = Limit(capacity = 300),
        val perClient: Limit = Limit(capacity = 60),
    )

    data class Limit(val capacity: Long, val period: Duration = Duration.ofMinutes(1))
}

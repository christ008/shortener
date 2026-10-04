package uy.ct.shortener.security.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `shortener.security.*` settings. A client is identified by the [clientIdClaim] of its access
 * token (`azp` by default), and that name is recorded as the owner of the links it creates. [RateLimit] holds the
 * token-bucket limits, which are per pod: [RateLimit.perIp] applies to all requests from one
 * client IP and [RateLimit.perClient] to requests from one authenticated client. A [Limit] holds
 * [Limit.capacity] requests, fully refilled over [Limit.period], one minute unless set.
 *
 * With [Dpop.required], which is the default, only access tokens bound to the client's key are
 * accepted (RFC 9449): they must arrive under the `DPoP` authorization scheme with a proof of
 * possession, and the `Bearer` scheme is refused. Turn it off only for development and tests. Spring
 * remembers each proof for 30 seconds to refuse replays, in memory and per pod, and accepts at most
 * 1,000 of them per client key in that time, so one key can make about 30 requests a second.
 * Access tokens must carry the JOSE type [accessTokenType] (RFC 9068), so that no other kind of
 * JWT from the same issuer is accepted in their place; leave it blank to skip the check.
 */
@ConfigurationProperties("shortener.security")
data class SecurityProperties(
    val clientIdClaim: String = "azp",
    val rateLimit: RateLimit = RateLimit(),
    val dpop: Dpop = Dpop(),
    val accessTokenType: String = "at+jwt",
) {

    data class Dpop(val required: Boolean = true)

    data class RateLimit(
        val perIp: Limit = Limit(capacity = 300),
        val perClient: Limit = Limit(capacity = 60),
    )

    data class Limit(val capacity: Long, val period: Duration = Duration.ofMinutes(1))
}

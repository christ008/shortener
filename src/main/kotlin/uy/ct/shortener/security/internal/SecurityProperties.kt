package uy.ct.shortener.security.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `shortener.security.*` settings.
 *
 * - [clientIdClaim]: the claim that names the owner of a token, recorded as the creator of the links it creates (`azp` by
 *   default, `owner` in the shipped configuration).
 * - [rateLimit]: token-bucket limits per instance. [RateLimit.perIp] covers all requests from one IP, [RateLimit.perClient]
 *   those of one authenticated client. A [Limit] holds [Limit.capacity] requests, refilled over [Limit.period] (one minute
 *   unless set).
 * - [dpop]: with [Dpop.required] (the default) only tokens bound to the client's key are accepted (RFC 9449): `DPoP` scheme
 *   with a proof, `Bearer` refused. Turn it off for development and tests only. Proofs are remembered 30 seconds, per
 *   instance, at most 1,000 per client key.
 * - [accessTokenType]: the JOSE type access tokens must carry (RFC 9068). Blank skips the check.
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

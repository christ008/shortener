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
 * - [Dpop.nonce]: with [Nonce.enabled] (the default) a proof must carry a nonce the server handed out in `DPoP-Nonce` (RFC 9449,
 *   section 9). The nonce is an HMAC of the current [Nonce.interval] (five minutes unless set) and is good for that one and the
 *   next. [Nonce.secret] must be the same on every instance, and in `production` it is required; elsewhere a blank one is a random
 *   secret for each process.
 * - [accessTokenType]: the JOSE type access tokens must carry (RFC 9068). Blank skips the check.
 */
@ConfigurationProperties("shortener.security")
data class SecurityProperties(
    val clientIdClaim: String = "azp",
    val rateLimit: RateLimit = RateLimit(),
    val dpop: Dpop = Dpop(),
    val accessTokenType: String = "at+jwt",
) {

    data class Dpop(val required: Boolean = true, val nonce: Nonce = Nonce())

    data class Nonce(val enabled: Boolean = true, val secret: String = "", val interval: Duration = Duration.ofMinutes(5))

    data class RateLimit(
        val perIp: Limit = Limit(capacity = 300),
        val perClient: Limit = Limit(capacity = 60),
    )

    data class Limit(val capacity: Long, val period: Duration = Duration.ofMinutes(1))
}

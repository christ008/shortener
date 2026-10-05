package uy.ct.shortener.security.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `shortener.security.*` settings.
 *
 * - [clientIdClaim]: the claim that names the owner of a token (`azp` by default, `owner` as the
 *   application configures it; Keycloak fills it with the client id for a service and the user id
 *   for a person). That name is recorded as the creator of the links the token creates.
 * - [rateLimit]: token-bucket limits, per pod. [RateLimit.perIp] covers all requests from one IP,
 *   [RateLimit.perClient] those of one authenticated client. A [Limit] holds [Limit.capacity]
 *   requests, refilled over [Limit.period] (one minute unless set).
 * - [dpop]: with [Dpop.required], the default, only tokens bound to the client's key are accepted
 *   (RFC 9449): `DPoP` scheme with a proof, `Bearer` refused. Turn it off only for development and
 *   tests. Proofs are remembered for 30 seconds to refuse replays, in memory per pod, at most 1,000
 *   per client key, so one key can make about 30 requests a second.
 * - [accessTokenType]: the JOSE type access tokens must carry (RFC 9068), so no other JWT from the
 *   same issuer is accepted in their place. Blank skips the check.
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

package uy.ct.shortener.security.internal

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import kotlin.math.max

/**
 * Applies a [RateLimiter] to every request and answers the excess with a `429` problem detail.
 *
 * - [keyOf] chooses what is limited and may return [RateLimitKey.Unlimited] to skip a request.
 * - Used per client IP before authentication and per authenticated client after it.
 * - Actuator paths are never limited.
 * - [name] must be distinct for each instance in the filter chain.
 */
class RateLimitFilter(
    private val name: String,
    private val limiter: RateLimiter,
    private val responder: SecurityProblemResponder,
    private val keyOf: (HttpServletRequest) -> RateLimitKey,
) : OncePerRequestFilter() {

    override fun getAlreadyFilteredAttributeName() = "${RateLimitFilter::class.java.name}.$name.FILTERED"

    override fun shouldNotFilter(request: HttpServletRequest) = request.requestURI.startsWith("/actuator")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        when (val key = keyOf(request)) {
            RateLimitKey.Unlimited -> chain.doFilter(request, response)
            is RateLimitKey.Of -> when (val decision = limiter.tryAcquire(key.value)) {
                RateLimitDecision.Allowed -> chain.doFilter(request, response)
                is RateLimitDecision.Limited ->
                    responder.tooManyRequests(request, response, max(1, decision.retryAfter.plusMillis(999).seconds), name, key.value)
            }
        }
    }
}

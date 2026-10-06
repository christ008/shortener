package uy.ct.shortener.security.internal

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import kotlin.math.max

/**
 * Applies a [RateLimiter] to every request and answers the excess with a 429 problem detail.
 *
 * - [keyOf] chooses what is limited, and may return [RateLimitKey.Unlimited] to skip a request.
 * - Used twice in the filter chain: per client IP before authentication, so token guessing is
 *   throttled, and per authenticated client after it.
 * - Actuator paths are never limited, so health probes cannot be throttled.
 * - Each instance needs a distinct [name]: `OncePerRequestFilter` remembers a filtered request by
 *   filter name, so two instances sharing one would skip each other.
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

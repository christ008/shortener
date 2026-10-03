package uy.ct.shortener.security.internal

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import kotlin.math.max

/**
 * Applies a [RateLimiter] to every request, rejecting excess ones with a 429 problem detail.
 * [keyOf] chooses what is limited and may return null to skip a request. It is used twice in the
 * filter chain: per client IP before authentication, so token guessing is throttled, and per
 * authenticated client after it. Actuator endpoints are never limited, so probes from the
 * kubelet cannot be throttled.
 *
 * Each instance needs a distinct [name]. `OncePerRequestFilter` remembers that a request was
 * already filtered by filter name, so two instances of this class sharing one would skip each
 * other.
 */
class RateLimitFilter(
    private val name: String,
    private val limiter: RateLimiter,
    private val responder: ProblemDetailResponder,
    private val keyOf: (HttpServletRequest) -> String?,
) : OncePerRequestFilter() {

    override fun getAlreadyFilteredAttributeName() = "${RateLimitFilter::class.java.name}.$name.FILTERED"

    override fun shouldNotFilter(request: HttpServletRequest) = request.requestURI.startsWith("/actuator")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val wait = keyOf(request)?.let(limiter::tryAcquire)
        if (wait == null) {
            chain.doFilter(request, response)
        } else {
            responder.tooManyRequests(response, max(1, wait.plusMillis(999).seconds))
        }
    }
}

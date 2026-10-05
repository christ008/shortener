package uy.ct.shortener.security.internal

import java.time.Duration

/**
 * What a [RateLimiter] says about a request: it is [Allowed], or [Limited] with how long to wait.
 */
sealed interface RateLimitDecision {

    data object Allowed : RateLimitDecision

    data class Limited(val retryAfter: Duration) : RateLimitDecision
}

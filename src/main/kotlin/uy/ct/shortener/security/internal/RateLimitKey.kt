package uy.ct.shortener.security.internal

/**
 * What a [RateLimitFilter] limits a request by: a [Of] key, or [Unlimited] for a request that this
 * filter does not limit, such as one that is not authenticated yet.
 */
sealed interface RateLimitKey {

    data object Unlimited : RateLimitKey

    data class Of(val value: String) : RateLimitKey
}

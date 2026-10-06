package uy.ct.shortener.security.internal

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import java.time.Duration

/**
 * In-memory token-bucket rate limiter keyed by a string, such as a client IP or an OAuth client id.
 *
 * - Buckets are held in a size-bounded Caffeine cache and expire once idle for a full refill period.
 * - State is per process.
 */
class RateLimiter(private val limit: SecurityProperties.Limit, maxKeys: Long = 100_000) {

    private val buckets = Caffeine.newBuilder()
        .maximumSize(maxKeys)
        .expireAfterAccess(limit.period)
        .build<String, Bucket>()

    fun tryAcquire(key: String): RateLimitDecision {
        val probe = buckets.get(key) { newBucket() }.tryConsumeAndReturnRemaining(1)
        return if (probe.isConsumed) RateLimitDecision.Allowed else RateLimitDecision.Limited(Duration.ofNanos(probe.nanosToWaitForRefill))
    }

    private fun newBucket(): Bucket = Bucket.builder()
        .addLimit(Bandwidth.builder().capacity(limit.capacity).refillGreedy(limit.capacity, limit.period).build())
        .build()
}

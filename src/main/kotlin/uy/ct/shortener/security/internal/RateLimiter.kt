package uy.ct.shortener.security.internal

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import java.time.Duration

/**
 * In-memory token-bucket rate limiter keyed by an arbitrary string, such as a client IP or an
 * API key name. Buckets live in a size-bounded Caffeine cache and expire once idle for a full
 * refill period, so a flood of distinct keys cannot exhaust memory and a forgotten key costs
 * nothing. State is per process: with several replicas the effective limit is the limit times
 * the replica count. [tryAcquire] returns null if the request is allowed, otherwise how long to
 * wait before retrying.
 */
class RateLimiter(private val limit: SecurityProperties.Limit, maxKeys: Long = 100_000) {

    private val buckets = Caffeine.newBuilder()
        .maximumSize(maxKeys)
        .expireAfterAccess(limit.period)
        .build<String, Bucket>()

    fun tryAcquire(key: String): Duration? {
        val probe = buckets.get(key) { newBucket() }.tryConsumeAndReturnRemaining(1)
        return if (probe.isConsumed) null else Duration.ofNanos(probe.nanosToWaitForRefill)
    }

    private fun newBucket(): Bucket = Bucket.builder()
        .addLimit(Bandwidth.builder().capacity(limit.capacity).refillGreedy(limit.capacity, limit.period).build())
        .build()
}

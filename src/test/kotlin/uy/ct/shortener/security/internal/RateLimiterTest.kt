package uy.ct.shortener.security.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class RateLimiterTest {

    private val limiter = RateLimiter(SecurityProperties.Limit(capacity = 2, period = Duration.ofMinutes(1)))

    @Test
    fun `allows requests up to the capacity then reports how long to wait`() {
        assertThat(limiter.tryAcquire("client")).isEqualTo(RateLimitDecision.Allowed)
        assertThat(limiter.tryAcquire("client")).isEqualTo(RateLimitDecision.Allowed)

        val decision = limiter.tryAcquire("client")

        assertThat(decision).isInstanceOf(RateLimitDecision.Limited::class.java)
        assertThat((decision as RateLimitDecision.Limited).retryAfter).isPositive.isLessThanOrEqualTo(Duration.ofMinutes(1))
    }

    @Test
    fun `limits each key on its own`() {
        repeat(2) { limiter.tryAcquire("noisy") }

        assertThat(limiter.tryAcquire("noisy")).isInstanceOf(RateLimitDecision.Limited::class.java)
        assertThat(limiter.tryAcquire("quiet")).isEqualTo(RateLimitDecision.Allowed)
    }
}

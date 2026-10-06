package uy.ct.shortener.shortlink.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `shortener.shortlink.redirect-cache.*` settings of the [RedirectCache].
 *
 * - [enabled]: when false, every redirect reads the database.
 * - [ttl]: how long an entry lives, and how long another instance can keep following a link after it was disabled.
 * - [maxEntries]: the entry cap.
 * - [staleIfError]: how long after it was last read a link is still followed when the database cannot be reached, and how
 *   long a link disabled elsewhere can keep redirecting during such an outage. Zero turns it off. Must not be shorter than [ttl].
 */
@ConfigurationProperties("shortener.shortlink.redirect-cache")
data class RedirectCacheProperties(
    val enabled: Boolean = true,
    val ttl: Duration = Duration.ofSeconds(30),
    val maxEntries: Long = 100_000,
    val staleIfError: Duration = Duration.ofMinutes(5),
) {
    init {
        require(!ttl.isNegative && !ttl.isZero) { "the redirect cache ttl must be positive, was $ttl" }
        require(maxEntries > 0) { "the redirect cache must hold at least one entry, was $maxEntries" }
        require(staleIfError.isZero || staleIfError >= ttl) {
            "the redirect cache's stale-if-error must be zero or at least the ttl ($ttl), was $staleIfError"
        }
    }
}

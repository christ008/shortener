package uy.ct.shortener.shortlink.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `shortener.shortlink.redirect-cache.*` settings of the [RedirectCache].
 *
 * - [enabled]: when false, every redirect reads the database.
 * - [ttl]: how long an entry lives. It is also how long another instance can keep following a link
 *   after it was disabled.
 * - [maxEntries]: the cap that gives the cache a known memory ceiling.
 */
@ConfigurationProperties("shortener.shortlink.redirect-cache")
data class RedirectCacheProperties(
    val enabled: Boolean = true,
    val ttl: Duration = Duration.ofSeconds(30),
    val maxEntries: Long = 100_000,
) {
    init {
        require(!ttl.isNegative && !ttl.isZero) { "the redirect cache ttl must be positive, was $ttl" }
        require(maxEntries > 0) { "the redirect cache must hold at least one entry, was $maxEntries" }
    }
}

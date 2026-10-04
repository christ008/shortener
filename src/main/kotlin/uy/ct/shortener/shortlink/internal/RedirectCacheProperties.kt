package uy.ct.shortener.shortlink.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `shortener.shortlink.redirect-cache.*` settings of the [RedirectCache]. An entry lives for [ttl],
 * which is also how long another instance can keep following a link after it was disabled, and at
 * most [maxEntries] are kept, so the memory used has a known ceiling. Turning it off with
 * [enabled] sends every redirect to the database.
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

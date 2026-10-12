package uy.ct.shortener.analytics.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `shortener.analytics.*` settings of the click counting.
 *
 * - [enabled]: when false nothing is counted and nothing is flushed.
 * - [flushInterval]: how often the counts held in memory are written to the database. A restart loses what was not flushed.
 * - [maxEntries]: the most distinct (link, hour, class) counts an instance holds. Past it new ones are dropped and counted as dropped.
 * - [batchSize]: the most counts written by one statement.
 */
@ConfigurationProperties("shortener.analytics")
data class AnalyticsProperties(
    val enabled: Boolean = true,
    val flushInterval: Duration = Duration.ofSeconds(10),
    val maxEntries: Int = 50_000,
    val batchSize: Int = 500,
) {
    init {
        require(!flushInterval.isNegative && !flushInterval.isZero) { "the analytics flush interval must be positive, was $flushInterval" }
        require(maxEntries > 0) { "analytics must hold at least one count, was $maxEntries" }
        require(batchSize in 1..maxEntries) { "the analytics batch size must be between 1 and the entry cap ($maxEntries), was $batchSize" }
    }
}

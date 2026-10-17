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
 * - [referrers]: how many referrer hosts an instance counts for a link in a day, and for how many (link, day) pairs it remembers them.
 */
@ConfigurationProperties("shortener.analytics")
data class AnalyticsProperties(
    val enabled: Boolean = true,
    val flushInterval: Duration = Duration.ofSeconds(10),
    val maxEntries: Int = 50_000,
    val batchSize: Int = 500,
    val referrers: Referrers = Referrers(),
) {

    /**
     * `shortener.analytics.referrers.*`, see [ReferrerAdmission].
     *
     * - [perLinkAndDay]: the hosts admitted for a link in a day, 50. A read shows the top 20.
     * - [tracked]: the (link, day) pairs whose hosts are remembered, 2,000. A pair holds at most [perLinkAndDay] host names, so this
     *   bounds the memory to a few MB even when every pair is full, and to far less in practice.
     */
    data class Referrers(val perLinkAndDay: Int = 50, val tracked: Long = 2_000) {
        init {
            require(perLinkAndDay > 0) { "an instance must admit at least one referrer host per link and day, was $perLinkAndDay" }
            require(tracked > 0) { "an instance must remember the hosts of at least one link and day, was $tracked" }
        }
    }

    init {
        require(!flushInterval.isNegative && !flushInterval.isZero) { "the analytics flush interval must be positive, was $flushInterval" }
        require(maxEntries > 0) { "analytics must hold at least one count, was $maxEntries" }
        require(batchSize in 1..maxEntries) { "the analytics batch size must be between 1 and the entry cap ($maxEntries), was $batchSize" }
    }
}

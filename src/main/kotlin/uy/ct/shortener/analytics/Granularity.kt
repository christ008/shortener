package uy.ct.shortener.analytics

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * How finely the clicks of a link are told apart in time: one bucket per [Hour] or per [Day], both in UTC.
 *
 * - Sealed, and each variant carries what differs between them: how a moment becomes the start of its bucket, where the next
 *   begins, the widest window it answers, the window it answers when none is asked for, and how long its buckets are kept. The domain
 *   never branches on the variant; only the persistence adapter does, to read the folded days for a window of days.
 * - [kept] is how long its buckets are kept: hours for 90 days, days for ever (ADR 0043).
 * - The interface has no method with a body: a method with a body makes the JVM initialise the interface, and with it [all], while
 *   [Hour] or [Day] is still being initialised, and [all] would hold a null.
 * - [wire] is the name in the API, and [unit] the one `date_trunc` takes.
 * - [parse] reads a wire name and refuses any other with [InvalidStatsWindowException].
 */
sealed interface Granularity {

    val wire: String

    val unit: String

    val maxSpan: Duration

    val defaultSpan: Duration

    val kept: Kept

    fun floor(moment: Instant): Instant

    fun next(bucket: Instant): Instant

    data object Hour : Granularity {
        override val wire = "hour"
        override val unit = "hour"
        override val maxSpan: Duration = Duration.ofDays(90)
        override val defaultSpan: Duration = Duration.ofHours(48)
        override val kept: Kept = Kept.For(Duration.ofDays(90), "Ask by day for older ones")
        override fun floor(moment: Instant): Instant = moment.truncatedTo(ChronoUnit.HOURS)
        override fun next(bucket: Instant): Instant = bucket.plus(1, ChronoUnit.HOURS)
    }

    data object Day : Granularity {
        override val wire = "day"
        override val unit = "day"
        override val maxSpan: Duration = Duration.ofDays(366)
        override val defaultSpan: Duration = Duration.ofDays(30)
        override val kept: Kept = Kept.Forever
        override fun floor(moment: Instant): Instant = moment.truncatedTo(ChronoUnit.DAYS)
        override fun next(bucket: Instant): Instant = bucket.plus(1, ChronoUnit.DAYS)
    }

    companion object {

        val all: List<Granularity> = listOf(Hour, Day)

        fun parse(text: String): Granularity =
            all.firstOrNull { it.wire == text }
                ?: throw InvalidStatsWindowException("Unknown granularity '$text'; use ${all.joinToString(" or ") { "'${it.wire}'" }}")
    }
}

/**
 * How long the buckets of a [Granularity] are kept: [For] a period, or [Forever].
 *
 * - [For.since] is the start of the oldest bucket still kept: the start of today, in UTC, less [For.period]. It is the rule the database's
 *   `analytics_retention` folds by (ADR 0043), so a window that starts earlier would read zeros that are not real. [For.otherwise] says
 *   what to ask for instead.
 */
sealed interface Kept {

    data class For(val period: Duration, val otherwise: String) : Kept {
        fun since(now: Instant): Instant = now.truncatedTo(ChronoUnit.DAYS).minus(period)
    }

    data object Forever : Kept
}

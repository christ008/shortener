package uy.ct.shortener.analytics

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * How finely the clicks of a link are told apart in time: one bucket per [Hour] or per [Day], both in UTC.
 *
 * - Sealed, and each variant carries what differs between them: how a moment becomes the start of its bucket, where the next
 *   begins, the widest window it answers and the window it answers when none is asked for. Nothing branches on the variant.
 * - [wire] is the name in the API, and [unit] the one `date_trunc` takes.
 * - [parse] reads a wire name and refuses any other with [InvalidStatsWindowException].
 */
sealed interface Granularity {

    val wire: String

    val unit: String

    val maxSpan: Duration

    val defaultSpan: Duration

    fun floor(moment: Instant): Instant

    fun next(bucket: Instant): Instant

    data object Hour : Granularity {
        override val wire = "hour"
        override val unit = "hour"
        override val maxSpan: Duration = Duration.ofDays(90)
        override val defaultSpan: Duration = Duration.ofHours(48)
        override fun floor(moment: Instant): Instant = moment.truncatedTo(ChronoUnit.HOURS)
        override fun next(bucket: Instant): Instant = bucket.plus(1, ChronoUnit.HOURS)
    }

    data object Day : Granularity {
        override val wire = "day"
        override val unit = "day"
        override val maxSpan: Duration = Duration.ofDays(366)
        override val defaultSpan: Duration = Duration.ofDays(30)
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

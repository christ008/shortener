package uy.ct.shortener.analytics

import java.time.Duration
import java.time.Instant

/**
 * The span of time a click count covers: the buckets of a [granularity] from [start] up to, and not including, [end].
 *
 * - [start] and [end] are bucket boundaries, so a window never ends in the middle of a count. [of] rounds a request outward: the
 *   bucket that holds `from` is the first and the one that holds `to` is the last.
 * - It is never empty and never wider than [Granularity.maxSpan]. Breaking either throws [InvalidStatsWindowException], so a
 *   window that exists can be answered.
 * - [buckets] are the starts of every bucket in it, in order.
 */
data class StatsWindow(val granularity: Granularity, val start: Instant, val end: Instant) {

    init {
        if (granularity.floor(start) != start || granularity.floor(end) != end) {
            throw InvalidStatsWindowException("A window of ${granularity.wire}s must begin and end on a ${granularity.wire} boundary, in UTC")
        }
        if (!start.isBefore(end)) throw InvalidStatsWindowException("The window must end after it begins: from $start, to $end")
        if (Duration.between(start, end) > granularity.maxSpan) {
            throw InvalidStatsWindowException("A window of ${granularity.wire}s covers at most ${granularity.maxSpan.toDays()} days, this one covers ${Duration.between(start, end).toDays()}")
        }
    }

    val buckets: List<Instant> get() = generateSequence(start) { granularity.next(it) }.takeWhile { it.isBefore(end) }.toList()

    companion object {

        /** The window that holds [from] up to [to], widened to whole buckets. `to` itself is outside it, unless it is a boundary. */
        fun of(granularity: Granularity, from: Instant, to: Instant): StatsWindow {
            if (!from.isBefore(to)) throw InvalidStatsWindowException("The window must end after it begins: from $from, to $to")
            val lastFloor = granularity.floor(to)
            val end = if (lastFloor == to) to else granularity.next(lastFloor)
            return StatsWindow(granularity, granularity.floor(from), end)
        }
    }
}

package uy.ct.shortener.analytics

import uy.ct.shortener.shortlink.ShortCode
import java.time.Instant

/**
 * Clicks, told apart by the kind of client that made them.
 *
 * - [visitors] is [desktop] plus [mobile]: the people. [bot] is crawlers, link previewers and command line clients, and is
 *   never part of it.
 * - [NONE] is a count of nothing, which is what a bucket with no clicks holds, so no bucket is ever absent.
 */
data class DeviceCounts(val desktop: Long, val mobile: Long, val bot: Long) {

    val visitors: Long get() = desktop + mobile

    operator fun plus(other: DeviceCounts) = DeviceCounts(desktop + other.desktop, mobile + other.mobile, bot + other.bot)

    companion object {
        val NONE = DeviceCounts(0, 0, 0)
    }
}

/** The clicks of one bucket of a window, from [start] up to the start of the next. */
data class ClickPoint(val start: Instant, val counts: DeviceCounts)

/**
 * The clicks a link had over a [window], one [ClickPoint] for each of its buckets and none missing.
 *
 * - [total] is the sum of the points.
 * - The counts are approximate: a crash, a full instance or a database outage loses clicks, and a bot that sends a browser's
 *   header counts as a visitor.
 */
data class ClickStats(val shortCode: ShortCode, val window: StatsWindow, val points: List<ClickPoint>) {

    val total: DeviceCounts get() = points.fold(DeviceCounts.NONE) { sum, point -> sum + point.counts }
}

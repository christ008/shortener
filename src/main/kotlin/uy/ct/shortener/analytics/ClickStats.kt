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

/** A host that referred [visitors] of a link. */
data class HostVisitors(val host: String, val visitors: Long)

/**
 * Where the visitors of a link came from: the [hosts] that referred most of them, most first, those that came [direct], and the
 * [other] ones: hosts past the ones shown, or past the ones an instance admitted that day.
 */
data class Referrers(val direct: Long, val hosts: List<HostVisitors>, val other: Long)

/** A language that [visitors] of a link asked for first. */
data class LanguageVisitors(val code: String, val visitors: Long)

/** The languages the visitors of a link asked for first, most first, and how many asked for none that could be read. */
data class Languages(val codes: List<LanguageVisitors>, val unknown: Long)

/**
 * Where the visitors of a link came from and which language they asked for, over the whole UTC days from [start] to [end]: these
 * counts are kept per day, so they cover the days that a window of hours touches.
 *
 * - Visitors only: bots are never in them.
 */
data class Breakdown(val start: Instant, val end: Instant, val referrers: Referrers, val languages: Languages)

/**
 * The clicks a link had over a [window], one [ClickPoint] for each of its buckets and none missing, and the [breakdown] of its visitors.
 *
 * - [total] is the sum of the points.
 * - The counts are approximate: a crash, a full instance or a database outage loses clicks, and a bot that sends a browser's
 *   header counts as a visitor.
 */
data class ClickStats(val shortCode: ShortCode, val window: StatsWindow, val points: List<ClickPoint>, val breakdown: Breakdown) {

    val total: DeviceCounts get() = points.fold(DeviceCounts.NONE) { sum, point -> sum + point.counts }
}

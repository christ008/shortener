package uy.ct.shortener.analytics.internal.web

import uy.ct.shortener.analytics.Breakdown
import uy.ct.shortener.analytics.ClickPoint
import uy.ct.shortener.analytics.ClickStats
import uy.ct.shortener.analytics.DeviceCounts
import uy.ct.shortener.analytics.Languages
import uy.ct.shortener.analytics.Referrers
import java.time.Instant

/**
 * API representation of [DeviceCounts]: [visitors] is [desktop] plus [mobile], and [bot] is counted apart.
 */
data class ClickCountsResponse(val visitors: Long, val desktop: Long, val mobile: Long, val bot: Long) {
    companion object {
        fun from(counts: DeviceCounts) = ClickCountsResponse(counts.visitors, counts.desktop, counts.mobile, counts.bot)
    }
}

/** One bucket of the series: the counts of the hour or day that begins at [start]. */
data class ClickPointResponse(val start: Instant, val visitors: Long, val desktop: Long, val mobile: Long, val bot: Long) {
    companion object {
        fun from(point: ClickPoint) = with(ClickCountsResponse.from(point.counts)) { ClickPointResponse(point.start, visitors, desktop, mobile, bot) }
    }
}

/** A host and the visitors it referred. */
data class HostVisitorsResponse(val host: String, val visitors: Long)

/** API representation of [Referrers]: those that came [direct], the top [hosts], most first, and the [other] ones. */
data class ReferrersResponse(val direct: Long, val hosts: List<HostVisitorsResponse>, val other: Long) {
    companion object {
        fun from(referrers: Referrers) =
            ReferrersResponse(referrers.direct, referrers.hosts.map { HostVisitorsResponse(it.host, it.visitors) }, referrers.other)
    }
}

/** A language code of ISO 639-1 and the visitors who asked for it first. */
data class LanguageVisitorsResponse(val code: String, val visitors: Long)

/** API representation of [Languages]: the [codes], most first, and the visitors whose language could not be read. */
data class LanguagesResponse(val codes: List<LanguageVisitorsResponse>, val unknown: Long) {
    companion object {
        fun from(languages: Languages) =
            LanguagesResponse(languages.codes.map { LanguageVisitorsResponse(it.code, it.visitors) }, languages.unknown)
    }
}

/**
 * API representation of [Breakdown]: where visitors came from and which language they asked for, over the whole UTC days from [from]
 * up to [to], which hold the window that was asked for. Visitors only.
 */
data class BreakdownResponse(val from: Instant, val to: Instant, val referrers: ReferrersResponse, val languages: LanguagesResponse) {
    companion object {
        fun from(breakdown: Breakdown) =
            BreakdownResponse(breakdown.start, breakdown.end, ReferrersResponse.from(breakdown.referrers), LanguagesResponse.from(breakdown.languages))
    }
}

/**
 * API representation of [ClickStats].
 *
 * - [from] and [to] are the window that was answered, widened to whole buckets: `from` is inclusive and `to` is not.
 * - [series] has a point for every bucket in it, with zeros where there were no clicks.
 * - [total] is the sum of the series.
 * - [breakdown] is where the visitors came from and which language they asked for, over whole UTC days.
 */
data class ClickStatsResponse(
    val shortCode: String,
    val granularity: String,
    val from: Instant,
    val to: Instant,
    val total: ClickCountsResponse,
    val series: List<ClickPointResponse>,
    val breakdown: BreakdownResponse,
) {
    companion object {
        fun from(stats: ClickStats) = ClickStatsResponse(
            shortCode = stats.shortCode.value,
            granularity = stats.window.granularity.wire,
            from = stats.window.start,
            to = stats.window.end,
            total = ClickCountsResponse.from(stats.total),
            series = stats.points.map { ClickPointResponse.from(it) },
            breakdown = BreakdownResponse.from(stats.breakdown),
        )
    }
}

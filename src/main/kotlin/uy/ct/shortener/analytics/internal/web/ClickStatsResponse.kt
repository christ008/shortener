package uy.ct.shortener.analytics.internal.web

import uy.ct.shortener.analytics.ClickPoint
import uy.ct.shortener.analytics.ClickStats
import uy.ct.shortener.analytics.DeviceCounts
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

/**
 * API representation of [ClickStats].
 *
 * - [from] and [to] are the window that was answered, widened to whole buckets: `from` is inclusive and `to` is not.
 * - [series] has a point for every bucket in it, with zeros where there were no clicks.
 * - [total] is the sum of the series.
 */
data class ClickStatsResponse(
    val shortCode: String,
    val granularity: String,
    val from: Instant,
    val to: Instant,
    val total: ClickCountsResponse,
    val series: List<ClickPointResponse>,
) {
    companion object {
        fun from(stats: ClickStats) = ClickStatsResponse(
            shortCode = stats.shortCode.value,
            granularity = stats.window.granularity.wire,
            from = stats.window.start,
            to = stats.window.end,
            total = ClickCountsResponse.from(stats.total),
            series = stats.points.map { ClickPointResponse.from(it) },
        )
    }
}

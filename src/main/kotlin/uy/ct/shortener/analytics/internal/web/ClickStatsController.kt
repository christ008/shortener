package uy.ct.shortener.analytics.internal.web

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uy.ct.shortener.analytics.ClickStatsService
import uy.ct.shortener.analytics.Granularity
import uy.ct.shortener.analytics.StatsWindow
import uy.ct.shortener.shortlink.ShortCode
import java.time.Clock
import java.time.Instant

/**
 * The HTTP API of the click counts. Failures are problem details.
 *
 * - `GET /api/short-links/{shortCode}/stats` answers the clicks of a link as a series of buckets and their total. The caller must be able
 *   to read the link: another client's link is `404`.
 * - `granularity` is `hour` or `day` (the default). `from` and `to` are instants in ISO-8601, and widen to whole buckets in UTC. Without
 *   them the window is the last 30 days of days, or the last 48 hours of hours, up to now.
 * - A window that is empty or wider than the granularity allows (90 days of hours, 366 days of days) is `400`.
 */
@RestController
class ClickStatsController(
    private val service: ClickStatsService,
    private val clock: Clock,
) {

    @GetMapping("/api/short-links/{shortCode}/stats")
    fun stats(
        @PathVariable shortCode: ShortCode,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "day") granularity: String,
    ): ClickStatsResponse {
        val unit = Granularity.parse(granularity)
        val end = to ?: clock.instant()
        val window = StatsWindow.of(unit, from ?: end.minus(unit.defaultSpan), end)
        return ClickStatsResponse.from(service.stats(shortCode, window))
    }
}

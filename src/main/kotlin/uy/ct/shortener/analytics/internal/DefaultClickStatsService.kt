package uy.ct.shortener.analytics.internal

import org.springframework.stereotype.Service
import uy.ct.shortener.analytics.Breakdown
import uy.ct.shortener.analytics.ClickPoint
import uy.ct.shortener.analytics.ClickStats
import uy.ct.shortener.analytics.ClickStatsService
import uy.ct.shortener.analytics.DeviceCounts
import uy.ct.shortener.analytics.Granularity
import uy.ct.shortener.analytics.HostVisitors
import uy.ct.shortener.analytics.InvalidStatsWindowException
import uy.ct.shortener.analytics.Kept
import uy.ct.shortener.analytics.Language
import uy.ct.shortener.analytics.LanguageVisitors
import uy.ct.shortener.analytics.Languages
import uy.ct.shortener.analytics.Referrer
import uy.ct.shortener.analytics.Referrers
import uy.ct.shortener.analytics.StatsWindow
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLinkService
import java.time.Clock

/**
 * Default [ClickStatsService].
 *
 * - Reads the link through [ShortLinkService.get] first, so the rule of who may see a link is that service's and a link of
 *   another client is not found. Nothing is read from the counts before it passes.
 * - Refuses a window that starts before the oldest bucket its granularity keeps ([Kept.For.since]), rather than answering zeros for
 *   what was folded: hours are kept for 90 days. Days are kept for ever, so a window of days is never refused for its age.
 * - Fills every bucket of the window: one the database has no row for is [DeviceCounts.NONE].
 * - The breakdown covers the whole UTC days the window touches, because it is counted per day, and whole months where it reaches days
 *   older than 730, which are kept by the month. Its start and end say which.
 * - It shows the [SHOWN_HOSTS] hosts with the most visitors, ties by name, and adds the rest to `other`, with what instances already
 *   counted as [Referrer.Other]. Every language is shown: there are only as many as ISO 639-1 has.
 */
@Service
class DefaultClickStatsService(
    private val shortLinks: ShortLinkService,
    private val repository: ClickStatsRepository,
    private val clock: Clock,
) : ClickStatsService {

    override fun stats(shortCode: ShortCode, window: StatsWindow): ClickStats {
        requireKept(window)
        shortLinks.get(shortCode)
        val counted = repository.read(shortCode, window)
            .groupBy({ it.bucket }, { it })
            .mapValues { (_, rows) -> rows.fold(DeviceCounts.NONE) { sum, row -> sum + row.counts() } }
        val points = window.buckets.map { ClickPoint(it, counted.getOrDefault(it, DeviceCounts.NONE)) }
        val days = StatsWindow.of(Granularity.Day, window.start, window.end)
        return ClickStats(shortCode, window, points, breakdown(days, repository.breakdown(shortCode, days)))
    }

    private fun requireKept(window: StatsWindow) {
        when (val kept = window.granularity.kept) {
            Kept.Forever -> Unit

            is Kept.For -> {
                val since = kept.since(clock.instant())
                if (window.start.isBefore(since)) {
                    throw InvalidStatsWindowException(
                        "${window.granularity.wire.replaceFirstChar { it.uppercase() }}s are kept for ${kept.period.toDays()} days, since $since; " +
                            "the window starts at ${window.start}. ${kept.otherwise}",
                    )
                }
            }
        }
    }

    private fun ClickRow.counts() = when (device) {
        DeviceClass.DESKTOP -> DeviceCounts(clicks, 0, 0)
        DeviceClass.MOBILE -> DeviceCounts(0, clicks, 0)
        DeviceClass.BOT -> DeviceCounts(0, 0, clicks)
    }

    private fun breakdown(days: StatsWindow, rows: List<BreakdownRow>): Breakdown {
        var direct = 0L
        var other = 0L
        var unknown = 0L
        val hosts = mutableMapOf<String, Long>()
        val codes = mutableMapOf<String, Long>()
        rows.forEach { row ->
            when (row) {
                is BreakdownRow.FromReferrer -> when (val referrer = row.referrer) {
                    Referrer.Direct -> direct += row.visitors
                    Referrer.Other -> other += row.visitors
                    is Referrer.Host -> hosts.merge(referrer.name, row.visitors, Long::plus)
                }

                is BreakdownRow.InLanguage -> when (val language = row.language) {
                    Language.Unknown -> unknown += row.visitors
                    is Language.Code -> codes.merge(language.value, row.visitors, Long::plus)
                }
            }
        }
        val ranked = hosts.ranked()
        val shown = ranked.take(SHOWN_HOSTS).map { (host, visitors) -> HostVisitors(host, visitors) }
        val unshown = ranked.drop(SHOWN_HOSTS).sumOf { (_, visitors) -> visitors }
        return Breakdown(
            start = (rows.map { it.start } + days.start).min(),
            end = (rows.map { it.end } + days.end).max(),
            referrers = Referrers(direct, shown, other + unshown),
            languages = Languages(codes.ranked().map { (code, visitors) -> LanguageVisitors(code, visitors) }, unknown),
        )
    }

    private fun Map<String, Long>.ranked(): List<Pair<String, Long>> =
        entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).map { it.key to it.value }

    private companion object {
        const val SHOWN_HOSTS = 20
    }
}

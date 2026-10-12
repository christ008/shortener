package uy.ct.shortener.analytics.internal

import org.springframework.stereotype.Service
import uy.ct.shortener.analytics.ClickPoint
import uy.ct.shortener.analytics.ClickStats
import uy.ct.shortener.analytics.ClickStatsService
import uy.ct.shortener.analytics.DeviceCounts
import uy.ct.shortener.analytics.StatsWindow
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLinkService

/**
 * Default [ClickStatsService].
 *
 * - Reads the link through [ShortLinkService.get] first, so the rule of who may see a link is that service's and a link of
 *   another client is not found. Nothing is read from the counts before it passes.
 * - Fills every bucket of the window: one the database has no row for is [DeviceCounts.NONE].
 */
@Service
class DefaultClickStatsService(
    private val shortLinks: ShortLinkService,
    private val repository: ClickStatsRepository,
) : ClickStatsService {

    override fun stats(shortCode: ShortCode, window: StatsWindow): ClickStats {
        shortLinks.get(shortCode)
        val counted = repository.read(shortCode, window)
            .groupBy({ it.bucket }, { it })
            .mapValues { (_, rows) -> rows.fold(DeviceCounts.NONE) { sum, row -> sum + row.counts() } }
        return ClickStats(shortCode, window, window.buckets.map { ClickPoint(it, counted.getOrDefault(it, DeviceCounts.NONE)) })
    }

    private fun ClickRow.counts() = when (device) {
        DeviceClass.DESKTOP -> DeviceCounts(clicks, 0, 0)
        DeviceClass.MOBILE -> DeviceCounts(0, clicks, 0)
        DeviceClass.BOT -> DeviceCounts(0, 0, clicks)
    }
}

package uy.ct.shortener.analytics.internal

import uy.ct.shortener.analytics.StatsWindow
import uy.ct.shortener.shortlink.ShortCode
import java.time.Instant

/** The clicks of one class of device in the bucket that starts at [bucket]. */
data class ClickRow(val bucket: Instant, val device: DeviceClass, val clicks: Long)

/**
 * Reads the counts [ClickStore] wrote.
 */
interface ClickStatsRepository {

    /**
     * Returns what was counted for [shortCode] inside [window], grouped by its buckets and classes. A bucket with nothing counted has no row.
     *
     * @throws uy.ct.shortener.shortlink.StorageUnavailableException if storage cannot be reached right now
     */
    fun read(shortCode: ShortCode, window: StatsWindow): List<ClickRow>
}

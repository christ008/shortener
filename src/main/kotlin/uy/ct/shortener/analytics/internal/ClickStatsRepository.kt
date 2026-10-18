package uy.ct.shortener.analytics.internal

import uy.ct.shortener.analytics.Language
import uy.ct.shortener.analytics.Referrer
import uy.ct.shortener.analytics.StatsWindow
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.StorageUnavailableException
import java.time.Instant

/** The clicks of one class of device in the bucket that starts at [bucket]. */
data class ClickRow(val bucket: Instant, val device: DeviceClass, val clicks: Long)

/**
 * What a link's visitors were counted under in a day: a [FromReferrer] or an [InLanguage], and how many.
 */
sealed interface BreakdownRow {

    val visitors: Long

    data class FromReferrer(val referrer: Referrer, override val visitors: Long) : BreakdownRow

    data class InLanguage(val language: Language, override val visitors: Long) : BreakdownRow
}

/**
 * Reads the counts [ClickStore] wrote.
 */
interface ClickStatsRepository {

    /**
     * Returns what was counted for [shortCode] inside [window], grouped by its buckets and classes. A bucket with nothing counted has no row.
     *
     * @throws StorageUnavailableException if storage cannot be reached right now
     */
    fun read(shortCode: ShortCode, window: StatsWindow): List<ClickRow>

    /**
     * Returns the visitors of [shortCode] over the days of [days], added up by referrer and by language. A value nobody was counted
     * under has no row.
     *
     * @throws StorageUnavailableException if storage cannot be reached right now
     */
    fun breakdown(shortCode: ShortCode, days: StatsWindow): List<BreakdownRow>
}

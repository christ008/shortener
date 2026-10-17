package uy.ct.shortener.analytics

import uy.ct.shortener.shortlink.ShortCode

/**
 * Tells how many times a link was followed.
 *
 * - Who may ask is the `shortlink` module's rule for reading a link: the client that owns it, or an administrator. The caller
 *   needs the scope to read links.
 * - Counts are read from what was written, whether or not counting is switched on now.
 */
fun interface ClickStatsService {

    /**
     * Returns the clicks of the link under [shortCode] over [window], a point for every bucket, zero where there was none.
     *
     * @throws uy.ct.shortener.shortlink.ShortLinkNotFoundException if none exists, or it belongs to another client
     * @throws uy.ct.shortener.shortlink.StorageUnavailableException if storage cannot be reached right now
     */
    fun stats(shortCode: ShortCode, window: StatsWindow): ClickStats
}

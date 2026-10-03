package uy.ct.shortener.shortlink

import java.net.URI

/**
 * Storage for [ShortLink]s, independent of how they are persisted. Short codes are unique. Both
 * operations throw [StorageUnavailableException] when storage cannot serve them right now.
 */
interface ShortLinkRepository {

    fun findByShortCode(shortCode: ShortCode): ShortLink?

    /**
     * Stores a link under [shortCode] unless that code is already taken, and returns the stored
     * link, or null if it was taken. The check and the insert are one atomic step, so of any
     * number of concurrent calls for the same code exactly one succeeds.
     */
    fun insertIfAbsent(shortCode: ShortCode, targetUrl: URI, createdBy: String): ShortLink?
}

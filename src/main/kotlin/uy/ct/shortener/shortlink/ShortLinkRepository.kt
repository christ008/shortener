package uy.ct.shortener.shortlink

import java.net.URI

/**
 * Storage for [ShortLink]s, independent of how they are persisted. Short codes are unique. Every
 * operation throws [StorageUnavailableException] when storage cannot serve it right now. It knows
 * nothing about who may do what; that is decided by method security on the service.
 */
interface ShortLinkRepository {

    fun findByShortCode(shortCode: ShortCode): ShortLink?

    /**
     * Stores a link under [shortCode] unless that code is already taken, and returns the stored
     * link, or null if it was taken. The check and the insert are one atomic step, so of any
     * number of concurrent calls for the same code exactly one succeeds.
     */
    fun insertIfAbsent(shortCode: ShortCode, targetUrl: URI, createdBy: String): ShortLink?

    /**
     * Returns up to [size] links in [order], only those created by [createdBy] when given, starting after [after]
     * (the first page when null). The page names where the next one starts, and only when another link exists, so
     * reading a page costs the same however far into the listing it is and a link created meanwhile cannot shift
     * the pages that follow. A link whose transaction commits after a reader has gone past its creation time is
     * missed by that reader and seen by the next.
     *
     * @throws IllegalArgumentException if [size] is not between 1 and [MAX_PAGE_SIZE], which would load every link
     */
    fun list(createdBy: String?, order: LinkOrder, size: Int, after: LinkCursor?): LinkPage

    /**
     * Disables the link under [shortCode] on behalf of [disabledBy] and returns it, or null if
     * there is no such link. Disabling is idempotent: an already disabled link is returned
     * unchanged, keeping who disabled it and when.
     */
    fun disable(shortCode: ShortCode, disabledBy: String): ShortLink?

    companion object {
        const val MAX_PAGE_SIZE = 1000
    }
}

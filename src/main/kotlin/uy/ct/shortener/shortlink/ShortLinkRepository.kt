package uy.ct.shortener.shortlink

import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
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
     * Returns the [pageable] page of links, only those created by [createdBy] when given. It can
     * be sorted by [SORTABLE_PROPERTIES], newest first when no sort is given, and ties are broken
     * by short code so pages never overlap. A [Slice] says whether another page follows without
     * counting every link.
     *
     * @throws InvalidSortException if sorted by another property
     */
    fun list(createdBy: String?, pageable: Pageable): Slice<ShortLink>

    /**
     * Disables the link under [shortCode] on behalf of [disabledBy] and returns it, or null if
     * there is no such link. Disabling is idempotent: an already disabled link is returned
     * unchanged, keeping who disabled it and when.
     */
    fun disable(shortCode: ShortCode, disabledBy: String): ShortLink?

    companion object {
        val SORTABLE_PROPERTIES = setOf("createdAt", "shortCode")
    }
}

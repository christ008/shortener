package uy.ct.shortener.shortlink

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.net.URI

/**
 * Storage for [ShortLink]s, independent of how they are persisted.
 *
 * - Short codes are unique.
 * - Every operation throws [StorageUnavailableException] when storage cannot serve it right now.
 * - It knows nothing about who may do what; method security on the service decides that.
 */
interface ShortLinkRepository {

    fun findByShortCode(shortCode: ShortCode): LinkLookup

    /**
     * Stores a link under [shortCode] unless the code is taken. The check and the insert are one
     * atomic step, so of any number of concurrent calls for a code exactly one is [InsertResult.Created].
     */
    fun insertIfAbsent(shortCode: ShortCode, targetUrl: URI, createdBy: String): InsertResult

    /**
     * Returns the [pageable] page of the links that match [filter], with the total number of matches.
     *
     * - Sorts by [SORTABLE_PROPERTIES], newest first when no sort is given.
     * - Ties are broken by short code, so pages never overlap.
     * - The total comes from a separate count, run only when the page cannot tell it: a page that ends the
     *   listing already knows it. A link created between the page and the count can make the two differ by one.
     * - A page past the end is empty, with the real total, so a client can recover.
     *
     * @throws InvalidSortException if sorted by another property
     */
    fun list(filter: CreatedByFilter, pageable: Pageable): Page<ShortLink>

    /**
     * Disables the link under [shortCode] on behalf of [disabledBy]. Idempotent: an already disabled
     * link is returned unchanged, keeping who disabled it and when.
     */
    fun disable(shortCode: ShortCode, disabledBy: String): LinkLookup

    companion object {
        val SORTABLE_PROPERTIES = setOf("createdAt", "shortCode")
    }
}

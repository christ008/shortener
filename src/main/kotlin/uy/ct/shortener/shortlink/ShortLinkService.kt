package uy.ct.shortener.shortlink

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

/**
 * Creates, finds, lists and disables short links, and resolves them to their targets.
 *
 * - Who may call each operation is declared on the implementation with method security. A caller
 *   without the scope is denied (403).
 * - A link that belongs to another client is reported as not found, never forbidden, so its
 *   existence is not revealed.
 * - Other failures are exceptions that carry their HTTP status.
 */
interface ShortLinkService {

    /**
     * Creates a link for [targetUrl] under a new unique code, owned by [createdBy].
     *
     * @throws InvalidTargetUrlException if [targetUrl] isn't an absolute http(s) URL
     * @throws ShortCodeExhaustionException if no unique code could be allocated
     * @throws StorageUnavailableException if storage cannot be reached right now
     */
    fun shorten(targetUrl: String, createdBy: String): ShortLink

    /**
     * Creates a link for [targetUrl] under the chosen [shortCode], owned by [createdBy].
     *
     * @throws InvalidTargetUrlException if [targetUrl] isn't an absolute http(s) URL
     * @throws ShortCodeUnavailableException if [shortCode] is taken or reserved
     * @throws StorageUnavailableException if storage cannot be reached right now
     */
    fun claim(shortCode: ShortCode, targetUrl: String, createdBy: String): ShortLink

    /**
     * Returns the link a redirect follows. Public: no caller is involved.
     *
     * An active link may come from an in-memory cache, so one disabled through another instance can
     * still resolve for up to the cache's time to live.
     *
     * @throws ShortLinkNotFoundException if none exists
     * @throws ShortLinkDisabledException if it has been disabled
     * @throws StorageUnavailableException if storage cannot be reached right now
     */
    fun resolve(shortCode: ShortCode): ShortLink

    /**
     * Returns the link under [shortCode], disabled or not, if the caller may see it.
     *
     * @throws ShortLinkNotFoundException if none exists, or it belongs to another client
     * @throws StorageUnavailableException if storage cannot be reached right now
     */
    fun get(shortCode: ShortCode): ShortLink

    /**
     * Lists a page of the links that match [filter], newest first unless [pageable] sorts otherwise, with the
     * total number of matches so a client can offer any page.
     *
     * A client may only list its own links ([CreatedByFilter.Only] with its name). An administrator may
     * list one client's or [CreatedByFilter.Anyone].
     *
     * @throws InvalidSortException if sorted by a property links cannot be listed by
     * @throws StorageUnavailableException if storage cannot be reached right now
     */
    fun list(filter: CreatedByFilter, pageable: Pageable): Page<ShortLink>

    /**
     * Disables the link under [shortCode] on behalf of [disabledBy], idempotently. A client may disable
     * its own links, and an administrator any link.
     *
     * @throws ShortLinkNotFoundException if none exists, or it belongs to another client
     * @throws StorageUnavailableException if storage cannot be reached right now
     */
    fun disable(shortCode: ShortCode, disabledBy: String)
}

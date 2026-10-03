package uy.ct.shortener.shortlink

import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice

/**
 * Creates, finds, lists and disables short links, and resolves them to their targets. Who may do
 * what is declared on the implementation with Spring method security, so an operation the caller
 * lacks the scope for fails with an access-denied error (HTTP 403), and a link that belongs to
 * another client is reported as not found rather than forbidden, so its existence is not
 * revealed. Other failures are exceptions that carry their HTTP status.
 */
interface ShortLinkService {

    /**
     * Creates a short link for [targetUrl] under a new unique code, owned by [createdBy].
     *
     * @throws InvalidTargetUrlException if [targetUrl] isn't an absolute http(s) URL
     * @throws ShortCodeExhaustionException if no unique code could be allocated
     * @throws StorageUnavailableException if storage cannot be reached right now
     */
    fun shorten(targetUrl: String, createdBy: String): ShortLink

    /**
     * Creates a short link for [targetUrl] under the caller-chosen [shortCode], owned by [createdBy].
     *
     * @throws InvalidTargetUrlException if [targetUrl] isn't an absolute http(s) URL
     * @throws ShortCodeUnavailableException if [shortCode] is already taken or reserved
     * @throws StorageUnavailableException if storage cannot be reached right now
     */
    fun claim(shortCode: ShortCode, targetUrl: String, createdBy: String): ShortLink

    /**
     * Returns the target of the short link under [shortCode]. Public: no caller is involved.
     *
     * @throws ShortLinkNotFoundException if none exists
     * @throws ShortLinkDisabledException if it has been disabled
     * @throws StorageUnavailableException if storage cannot be reached right now
     */
    fun resolve(shortCode: ShortCode): ShortLink

    /**
     * Returns the short link under [shortCode], including a disabled one, if the caller may see it.
     *
     * @throws ShortLinkNotFoundException if none exists, or it belongs to another client
     * @throws StorageUnavailableException if storage cannot be reached right now
     */
    fun get(shortCode: ShortCode): ShortLink

    /**
     * Lists links, newest first unless [pageable] sorts otherwise. A client may only list its own
     * links, so [createdBy] must be its own name. An administrator may list those of any one client,
     * or every link when [createdBy] is null.
     *
     * @throws InvalidSortException if sorted by a property links cannot be listed by
     * @throws StorageUnavailableException if storage cannot be reached right now
     */
    fun list(createdBy: String?, pageable: Pageable): Slice<ShortLink>

    /**
     * Disables the short link under [shortCode] on behalf of [disabledBy], idempotently. A client
     * may disable its own links, and an administrator any link.
     *
     * @throws ShortLinkNotFoundException if none exists, or it belongs to another client
     * @throws StorageUnavailableException if storage cannot be reached right now
     */
    fun disable(shortCode: ShortCode, disabledBy: String)
}

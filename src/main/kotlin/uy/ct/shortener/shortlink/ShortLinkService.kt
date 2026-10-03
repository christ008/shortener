package uy.ct.shortener.shortlink

/**
 * Creates short links and resolves them back to their targets.
 * Failures are thrown as exceptions that carry their HTTP status.
 */
interface ShortLinkService {

    /**
     * Creates a short link for [targetUrl] under a new unique code, recorded as created by
     * [createdBy].
     *
     * @throws InvalidTargetUrlException if [targetUrl] isn't an absolute http(s) URL
     * @throws ShortCodeExhaustionException if no unique code could be allocated
     */
    fun shorten(targetUrl: String, createdBy: String): ShortLink

    /**
     * Creates a short link for [targetUrl] under the caller-chosen [shortCode], recorded as
     * created by [createdBy].
     *
     * @throws InvalidTargetUrlException if [targetUrl] isn't an absolute http(s) URL
     * @throws ShortCodeUnavailableException if [shortCode] is already taken or reserved
     */
    fun claim(shortCode: ShortCode, targetUrl: String, createdBy: String): ShortLink

    /**
     * Returns the short link stored under [shortCode].
     *
     * @throws ShortLinkNotFoundException if none exists
     */
    fun resolve(shortCode: ShortCode): ShortLink
}

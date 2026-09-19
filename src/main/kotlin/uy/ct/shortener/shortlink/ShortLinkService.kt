package uy.ct.shortener.shortlink

interface ShortLinkService {

    /** @throws InvalidTargetUrlException if [targetUrl] isn't a parseable absolute http(s) address. */
    fun shorten(targetUrl: String): ShortLink

    /** @throws ShortLinkNotFoundException if no link exists for [shortCode]. */
    fun resolve(shortCode: ShortCode): ShortLink
}

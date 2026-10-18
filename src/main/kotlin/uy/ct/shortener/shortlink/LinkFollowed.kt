package uy.ct.shortener.shortlink

/**
 * Published when a visitor followed a link: a `GET` of an active code that answered a redirect.
 *
 * - Not published for a `HEAD`, an unknown code or a disabled link.
 * - [userAgent], [referer] and [acceptLanguage] are the headers as the visitor sent them, empty when absent. They are not stored:
 *   what listens reads the class of the client, the referrer's host and the language from them, and keeps those.
 * - Delivered in the request's own thread, so a listener must not block or fail.
 */
data class LinkFollowed(val shortCode: ShortCode, val userAgent: String, val referer: String, val acceptLanguage: String)

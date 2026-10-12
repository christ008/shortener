package uy.ct.shortener.shortlink

/**
 * Published when a visitor followed a link: a `GET` of an active code that answered a redirect.
 *
 * - Not published for a `HEAD`, an unknown code or a disabled link.
 * - [userAgent] is the header as the visitor sent it, empty when absent. It is not stored: what listens classifies it and
 *   keeps the class.
 * - Delivered in the request's own thread, so a listener must not block or fail.
 */
data class LinkFollowed(val shortCode: ShortCode, val userAgent: String)

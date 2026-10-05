package uy.ct.shortener.shortlink

import java.time.Instant

/**
 * The order links are listed in: by creation time, and by short code among links created at the same time, so the
 * order is total and a position in it can be named.
 */
enum class LinkOrder { NEWEST_FIRST, OLDEST_FIRST }

/**
 * A position in a listing: the creation time and code of the last link of a page, after which the next page starts.
 * Naming where the previous page ended, instead of how many links to skip, keeps the cost of a page independent of
 * how far into the listing it is, and keeps pages from shifting when links are created meanwhile.
 */
data class LinkCursor(val createdAt: Instant, val shortCode: ShortCode)

/**
 * A page of links. [next] is where the page after this one starts, or null when this is the last. It is set only when
 * another link exists, so a client never follows it to an empty page.
 */
data class LinkPage(val items: List<ShortLink>, val next: LinkCursor?)

package uy.ct.shortener.shortlink.internal.web

import org.springframework.data.domain.Page
import uy.ct.shortener.shortlink.Actor
import uy.ct.shortener.shortlink.LinkStatus
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import java.net.URI
import java.time.Instant

/**
 * API representation of a [ShortLink].
 *
 * - [shortUrl] is the URL to share, `/{shortCode}` on the host the request was made to. The representation itself is at
 *   `/api/short-links/{shortCode}`.
 * - [createdBy] is absent for links stored before the creator was recorded.
 * - [disabledAt] is absent while the link is active.
 *
 * Absent fields are JSON null, which is what the API contract says.
 */
data class ShortLinkResponse(
    val shortCode: String,
    val shortUrl: String,
    val targetUrl: String,
    val createdBy: String?,
    val createdAt: Instant,
    val disabledAt: Instant?,
) {
    companion object {
        fun from(shortLink: ShortLink, shortUrl: URI) = ShortLinkResponse(
            shortCode = shortLink.shortCode.value,
            shortUrl = shortUrl.toString(),
            targetUrl = shortLink.targetUrl.toString(),
            createdBy = when (val creator = shortLink.createdBy) {
                is Actor.Client -> creator.name
                Actor.Unknown -> null
            },
            createdAt = shortLink.createdAt,
            disabledAt = when (val status = shortLink.status) {
                is LinkStatus.Disabled -> status.at
                LinkStatus.Active -> null
            },
        )
    }
}

/**
 * One page of links, as Spring's `Pageable` resolver asked for it (`page`, `size` and `sort`).
 *
 * - [page] is zero-based and [size] is the size that applied.
 * - [totalItems] and [totalPages] count every match, so a client can offer any page, including the last.
 * - [hasNext] says whether a page follows this one.
 * - Each item carries its `shortUrl`, from the `shortUrlOf` the page was built with.
 */
data class ShortLinkPageResponse(
    val items: List<ShortLinkResponse>,
    val page: Int,
    val size: Int,
    val hasNext: Boolean,
    val totalItems: Long,
    val totalPages: Int,
) {
    companion object {
        fun from(page: Page<ShortLink>, shortUrlOf: (ShortCode) -> URI) = ShortLinkPageResponse(
            items = page.content.map { ShortLinkResponse.from(it, shortUrlOf(it.shortCode)) },
            page = page.number,
            size = page.size,
            hasNext = page.hasNext(),
            totalItems = page.totalElements,
            totalPages = page.totalPages,
        )
    }
}

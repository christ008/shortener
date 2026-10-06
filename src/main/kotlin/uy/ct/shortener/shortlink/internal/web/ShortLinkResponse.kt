package uy.ct.shortener.shortlink.internal.web

import org.springframework.data.domain.Page
import uy.ct.shortener.shortlink.Actor
import uy.ct.shortener.shortlink.LinkStatus
import uy.ct.shortener.shortlink.ShortLink
import java.time.Instant

/**
 * API representation of a [ShortLink].
 *
 * - [createdBy] is absent for links stored before the creator was recorded.
 * - [disabledAt] is absent while the link is active.
 *
 * Absent fields are JSON null, which is what the API contract says.
 */
data class ShortLinkResponse(
    val shortCode: String,
    val targetUrl: String,
    val createdBy: String?,
    val createdAt: Instant,
    val disabledAt: Instant?,
) {
    companion object {
        fun from(shortLink: ShortLink) = ShortLinkResponse(
            shortCode = shortLink.shortCode.value,
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
        fun from(page: Page<ShortLink>) = ShortLinkPageResponse(
            items = page.content.map(ShortLinkResponse::from),
            page = page.number,
            size = page.size,
            hasNext = page.hasNext(),
            totalItems = page.totalElements,
            totalPages = page.totalPages,
        )
    }
}

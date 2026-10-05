package uy.ct.shortener.shortlink.internal.web

import org.springframework.data.domain.Slice
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
            createdBy = (shortLink.createdBy as? Actor.Client)?.name,
            createdAt = shortLink.createdAt,
            disabledAt = (shortLink.status as? LinkStatus.Disabled)?.at,
        )
    }
}

/**
 * One page of links, as Spring's `Pageable` resolver asked for it (`page`, `size` and `sort`).
 * [hasNext] says whether another page follows, without counting every link.
 */
data class ShortLinkPageResponse(val items: List<ShortLinkResponse>, val page: Int, val size: Int, val hasNext: Boolean) {
    companion object {
        fun from(slice: Slice<ShortLink>) = ShortLinkPageResponse(
            items = slice.content.map(ShortLinkResponse::from),
            page = slice.number,
            size = slice.size,
            hasNext = slice.hasNext(),
        )
    }
}

package uy.ct.shortener.shortlink.internal.web

import uy.ct.shortener.shortlink.LinkOrder
import uy.ct.shortener.shortlink.LinkPage
import uy.ct.shortener.shortlink.ShortLink
import java.time.Instant

/**
 * API representation of a [ShortLink]. [disabledAt] is null while the link is active.
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
            createdBy = shortLink.createdBy,
            createdAt = shortLink.createdAt,
            disabledAt = shortLink.disabledAt,
        )
    }
}

/**
 * One page of links. [size] is the page size that applied, after the maximum was enforced. [nextCursor] is where the next
 * page starts, to be passed back unchanged as `cursor`, and is null on the last page.
 */
data class ShortLinkPageResponse(val items: List<ShortLinkResponse>, val size: Int, val nextCursor: String?) {
    companion object {
        fun from(page: LinkPage, order: LinkOrder, size: Int) = ShortLinkPageResponse(
            items = page.items.map(ShortLinkResponse::from),
            size = size,
            nextCursor = page.next?.let { ListCursorCodec.encode(it, order) },
        )
    }
}

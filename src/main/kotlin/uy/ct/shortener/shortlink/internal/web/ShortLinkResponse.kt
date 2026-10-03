package uy.ct.shortener.shortlink.internal.web

import org.springframework.data.domain.Slice
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
 * One page of links, as Spring's `Pageable` resolver asked for it (`page`, `size` and `sort`
 * parameters). [hasNext] says whether another page follows, without counting every link.
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

package uy.ct.shortener.shortlink.internal.web

import uy.ct.shortener.shortlink.ShortLink
import java.time.Instant

/**
 * API representation of a [ShortLink].
 */
data class ShortLinkResponse(
    val shortCode: String,
    val targetUrl: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(shortLink: ShortLink) = ShortLinkResponse(
            shortCode = shortLink.shortCode.value,
            targetUrl = shortLink.targetUrl.toString(),
            createdAt = shortLink.createdAt,
        )
    }
}

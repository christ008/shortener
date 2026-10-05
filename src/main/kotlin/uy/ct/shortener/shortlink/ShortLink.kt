package uy.ct.shortener.shortlink

import java.net.URI
import java.time.Instant

/**
 * A stored mapping from a [shortCode] to an absolute http(s) [targetUrl].
 *
 * - Immutable, except that [status] moves once from [LinkStatus.Active] to [LinkStatus.Disabled].
 * - [createdBy] is [Actor.Unknown] for links stored before the creator was recorded.
 * - [createdAt] is assigned by the database.
 * - [requireValidTargetUrl] checks a target before it is stored.
 */
data class ShortLink(
    val shortCode: ShortCode,
    val targetUrl: URI,
    val createdBy: Actor,
    val createdAt: Instant,
    val status: LinkStatus = LinkStatus.Active,
) {

    val isDisabled: Boolean get() = status is LinkStatus.Disabled

    init {
        requireValidTargetUrl(targetUrl)
    }

    companion object {
        fun requireValidTargetUrl(targetUrl: URI) {
            require(targetUrl.isAbsolute) { "targetUrl must be an absolute URI, was '$targetUrl'" }
            require(targetUrl.scheme == "http" || targetUrl.scheme == "https") {
                "targetUrl scheme must be http or https, was '${targetUrl.scheme}'"
            }
        }
    }
}

package uy.ct.shortener.shortlink

import java.net.URI
import java.time.Instant

/**
 * A stored short link: a [shortCode] mapped to an absolute http(s) [targetUrl]. Immutable, except
 * that it can be disabled, which is recorded in [disabledAt] and [disabledBy] and never undone.
 * [createdBy] is the name of the client that created it, and is null for links that predate that
 * column. [createdAt] is assigned by the database. [requireValidTargetUrl] checks a target before
 * it is stored.
 */
data class ShortLink(
    val shortCode: ShortCode,
    val targetUrl: URI,
    val createdBy: String?,
    val createdAt: Instant,
    val disabledAt: Instant? = null,
    val disabledBy: String? = null,
) {

    val isDisabled: Boolean get() = disabledAt != null

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

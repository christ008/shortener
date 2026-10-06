package uy.ct.shortener.shortlink.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `shortener.shortlink.target-urls.*` settings.
 *
 * - [allowedHosts]: the hosts the service shortens links to: a host name, or `*.` and a domain for its subdomains
 *   (`example.com`, `*.example.org`).
 * - [allowAny]: accepts every host on purpose. Without a list and without this, development accepts every host and
 *   `production` refuses to start. Setting both is refused.
 *
 * From the environment: `SHORTENER_SHORTLINK_TARGETURLS_ALLOWEDHOSTS=example.com,*.example.org`, or
 * `SHORTENER_SHORTLINK_TARGETURLS_ALLOWANY=true`.
 */
@ConfigurationProperties("shortener.shortlink.target-urls")
data class TargetUrlProperties(val allowedHosts: List<String> = emptyList(), val allowAny: Boolean = false) {

    init {
        require(!(allowAny && allowedHosts.isNotEmpty())) { "target-urls.allow-any and target-urls.allowed-hosts say opposite things" }
    }

    val isDecided: Boolean get() = allowAny || allowedHosts.isNotEmpty()

    /** The policy these settings describe. */
    fun toPolicy(): TargetUrlPolicy = if (allowedHosts.isEmpty()) AnyTarget else AllowedHosts(allowedHosts)
}

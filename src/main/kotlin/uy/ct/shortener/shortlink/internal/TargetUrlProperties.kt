package uy.ct.shortener.shortlink.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `shortener.shortlink.target-urls.*` settings.
 *
 * - [allowedHosts]: the hosts the service shortens links to. Empty, the default, accepts every host. An entry is a
 *   host name or `*.` and a domain for its subdomains, for example `example.com` and `*.example.org`.
 *
 * From the environment: `SHORTENER_SHORTLINK_TARGETURLS_ALLOWEDHOSTS=example.com,*.example.org`.
 */
@ConfigurationProperties("shortener.shortlink.target-urls")
data class TargetUrlProperties(val allowedHosts: List<String> = emptyList()) {

    /** The policy these settings describe. */
    fun toPolicy(): TargetUrlPolicy = if (allowedHosts.isEmpty()) AnyTarget else AllowedHosts(allowedHosts)
}

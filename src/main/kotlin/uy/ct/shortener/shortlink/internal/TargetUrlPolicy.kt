package uy.ct.shortener.shortlink.internal

import uy.ct.shortener.shortlink.TargetUrlNotAllowedException
import java.net.URI

/**
 * Which targets this service will shorten, beyond being an absolute http(s) URL.
 *
 * - [AnyTarget] accepts every host. Used when no allowlist is configured.
 * - [AllowedHosts] accepts only the configured hosts.
 */
fun interface TargetUrlPolicy {

    /** Returns [target], or throws [TargetUrlNotAllowedException] if its host is not accepted. */
    fun require(target: URI): URI
}

object AnyTarget : TargetUrlPolicy {
    override fun require(target: URI): URI = target
}

/**
 * Accepts a target whose host is one of [hosts]. An entry is a host name, matched exactly, or `*.` followed by a
 * domain, which matches any subdomain of it but not the domain itself. Case is ignored.
 */
class AllowedHosts(hosts: Collection<String>) : TargetUrlPolicy {

    private val exact: Set<String>
    private val subdomainsOf: Set<String>

    init {
        require(hosts.isNotEmpty()) { "an allowlist needs at least one host" }
        val normalized = hosts.map { it.trim().lowercase() }
        require(normalized.none { it.isEmpty() || it == "*." }) { "a host in the allowlist must not be blank" }
        exact = normalized.filterNot { it.startsWith("*.") }.toSet()
        subdomainsOf = normalized.filter { it.startsWith("*.") }.map { it.removePrefix("*.") }.toSet()
    }

    override fun require(target: URI): URI {
        val host = target.host.orEmpty().lowercase()
        val allowed = host in exact || subdomainsOf.any { host.endsWith(".$it") }
        return if (allowed) target else throw TargetUrlNotAllowedException(host)
    }
}

package uy.ct.shortener.security.internal

import jakarta.servlet.http.HttpServletRequest
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate

/**
 * Who is on the other end of the TLS connection, as the certificate it presented says.
 *
 * - [Identified]: the URI names in the certificate's subject alternative names, such as `spiffe://shortener.internal/prod/edge`.
 * - [Unidentified]: no client certificate, or one with no URI name, which names nobody. The server asks for a certificate (`server.ssl.client-auth=need`),
 *   so the first is a request that did not come through TLS with one, such as a test over plain HTTP.
 *
 * A certificate that lists several URI names is as many identities: any of them in the allowed set serves the request.
 *
 * The chain was verified by TLS before the request reached the application; this only reads the name.
 */
sealed interface PeerIdentity {

    data class Identified(val names: Set<String>) : PeerIdentity

    data object Unidentified : PeerIdentity

    fun isAmong(allowed: Set<String>): Boolean = this is Identified && names.any { it in allowed }

    companion object {
        const val CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate"
        private const val URI_NAME = 6

        fun of(request: HttpServletRequest): PeerIdentity {
            val chain = request.getAttribute(CERTIFICATE_ATTRIBUTE)
            val leaf = when (val first = (chain as? Array<*>)?.firstOrNull()) {
                is X509Certificate -> first
                else -> return Unidentified
            }
            val names = try {
                leaf.subjectAlternativeNames.orEmpty().filter { it[0] == URI_NAME }.map { it[1].toString() }.toSet()
            } catch (_: CertificateParsingException) {
                emptySet()
            }
            return if (names.isEmpty()) Unidentified else Identified(names)
        }
    }
}

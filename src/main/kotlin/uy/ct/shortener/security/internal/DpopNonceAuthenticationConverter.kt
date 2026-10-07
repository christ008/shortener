package uy.ct.shortener.security.internal

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.server.resource.authentication.DPoPAuthenticationToken
import org.springframework.security.web.authentication.AuthenticationConverter
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.util.Base64

/**
 * Requires the `nonce` claim of a DPoP proof to be one that [nonces] accepts (RFC 9449, section 9).
 *
 * - A proof whose nonce is missing, wrong or old fails with the OAuth error [USE_DPOP_NONCE], which the security chain answers
 *   as a `401` with the current nonce in `DPoP-Nonce`.
 * - A proof that cannot be read is passed on, and Spring Security refuses it as an invalid proof.
 * - Everything else about the proof is checked by the DPoP provider, after this.
 */
class DpopNonceAuthenticationConverter(
    private val delegate: AuthenticationConverter,
    private val nonces: DpopNonces,
) : AuthenticationConverter {

    override fun convert(request: HttpServletRequest): Authentication? {
        val authentication = delegate.convert(request)
        if (authentication is DPoPAuthenticationToken) {
            val proof = claimsOf(authentication.dPoPProof)
            if (proof != null && !nonces.accepts(proof.path("nonce").stringValue(null))) {
                throw OAuth2AuthenticationException(OAuth2Error(USE_DPOP_NONCE, "The DPoP proof needs the current server nonce", null))
            }
        }
        return authentication
    }

    private fun claimsOf(proof: String) =
        try {
            proof.split('.').takeIf { it.size == JWT_PARTS }?.let { JSON.readTree(DECODER.decode(it[1])) }
        } catch (unreadable: JacksonException) {
            null
        } catch (notBase64: IllegalArgumentException) {
            null
        }

    companion object {
        const val USE_DPOP_NONCE = "use_dpop_nonce"
        private const val JWT_PARTS = 3
        private val JSON = JsonMapper.builder().build()
        private val DECODER = Base64.getUrlDecoder()
    }
}

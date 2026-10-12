package uy.ct.shortener.security.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken
import org.springframework.security.oauth2.server.resource.authentication.DPoPAuthenticationToken
import org.springframework.security.web.authentication.AuthenticationConverter
import java.time.Duration
import java.util.Base64
import kotlin.test.assertFailsWith

/**
 * The nonce of a DPoP proof is checked before Spring Security looks at the rest of the proof, with no Spring context: the
 * delegate hands the converter whatever token a test names.
 *
 * - A proof that can be read and lacks the current nonce is a request for it.
 * - A proof that cannot be read is passed on, for Spring Security to refuse as an invalid proof, and so is anything that is not a
 *   DPoP token.
 */
class DpopNonceAuthenticationConverterTest {

    private val nonces = HmacDpopNonces("a-secret-shared-by-every-instance".toByteArray(), Duration.ofMinutes(5))

    private val request = MockHttpServletRequest("GET", "/api/short-links")

    private fun converter(token: Authentication?, nonces: DpopNonces = this.nonces) =
        DpopNonceAuthenticationConverter(AuthenticationConverter { token }, nonces)

    private fun encode(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    private fun proofWith(claims: String) = "${encode("""{"alg":"ES256"}""")}.${encode(claims)}.signature"

    private fun dpop(proof: String) = DPoPAuthenticationToken("access-token", proof, "GET", "https://example.com/api/short-links")

    @Test
    fun `a proof with the current nonce is passed on`() {
        val token = dpop(proofWith("""{"nonce":"${nonces.current()}"}"""))

        assertThat(converter(token).convert(request)).isSameAs(token)
    }

    @Test
    fun `a proof without a nonce is a request for the current one`() {
        val failure = assertFailsWith<OAuth2AuthenticationException> { converter(dpop(proofWith("""{"jti":"1"}"""))).convert(request) }

        assertThat(failure.error.errorCode).isEqualTo(DpopNonceAuthenticationConverter.USE_DPOP_NONCE)
    }

    @Test
    fun `a proof with a nonce that is not the current one is a request for the current one`() {
        val failure = assertFailsWith<OAuth2AuthenticationException> {
            converter(dpop(proofWith("""{"nonce":"made-up"}"""))).convert(request)
        }

        assertThat(failure.error.errorCode).isEqualTo(DpopNonceAuthenticationConverter.USE_DPOP_NONCE)
    }

    @Test
    fun `a proof whose nonce is not text is a request for the current nonce`() {
        assertFailsWith<OAuth2AuthenticationException> { converter(dpop(proofWith("""{"nonce":42}"""))).convert(request) }
    }

    @Test
    fun `a proof that cannot be read is passed on, whatever is wrong with it`() {
        val unreadable = listOf(
            "not-a-jwt",
            "only.two",
            "four.parts.of.proof",
            "header.!!not-base64!!.signature",
            "header.${encode("this is not json")}.signature",
        )

        unreadable.forEach { proof ->
            val token = dpop(proof)
            assertThat(converter(token).convert(request)).describedAs(proof).isSameAs(token)
        }
    }

    @Test
    fun `a token that is not a DPoP token is passed on, and so is no token`() {
        val bearer = BearerTokenAuthenticationToken("access-token")

        assertThat(converter(bearer).convert(request)).isSameAs(bearer)
        assertThat(converter(null).convert(request)).isNull()
    }

    @Test
    fun `when nonces are not required any readable proof is passed on`() {
        val token = dpop(proofWith("""{"jti":"1"}"""))

        assertThat(converter(token, NoDpopNonces).convert(request)).isSameAs(token)
    }
}

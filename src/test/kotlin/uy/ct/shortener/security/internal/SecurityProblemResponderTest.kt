package uy.ct.shortener.security.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import uy.ct.shortener.LogCapture

/**
 * What a client is told when the security chain turns it away, and what the operator is told about it, with no Spring context
 * and no database: a small resolver keeps the problem the responder hands to MVC.
 *
 * - The status of the response, the problem it carries and its `WWW-Authenticate` or `Retry-After` header.
 * - The event each failure raises, which is none for a responder built without events.
 */
class SecurityProblemResponderTest {

    private class RecordingResolver : HandlerExceptionResolver {
        val problems = mutableListOf<SecurityProblem>()

        override fun resolveException(request: HttpServletRequest, response: HttpServletResponse, handler: Any?, ex: Exception): ModelAndView? {
            problems += ex as SecurityProblem
            return null
        }
    }

    private val resolver = RecordingResolver()

    private val meters = SimpleMeterRegistry()

    private val response = MockHttpServletResponse()

    private fun responder(dpopRequired: Boolean = false, withEvents: Boolean = true) =
        SecurityProblemResponder(resolver, dpopRequired, if (withEvents) SecurityEvents(meters) else null)

    private fun request(authorization: String? = null) = MockHttpServletRequest("GET", "/api/short-links").apply {
        authorization?.let { addHeader(HttpHeaders.AUTHORIZATION, it) }
    }

    private fun count(type: String) = meters.counter(SecurityEvents.METRIC, "type", type).count()

    private fun challenges() = resolver.problems.single().headers[HttpHeaders.WWW_AUTHENTICATE].orEmpty()

    private fun oauthError(code: String) = OAuth2AuthenticationException(OAuth2Error(code, "details that must not be reported", null))

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `each OAuth error has the status RFC 6750 and RFC 9449 give it, a 400 for a request that is not well formed and a 401 for a token or a proof that is wrong`() {
        mapOf(
            "invalid_request" to 400,
            "invalid_token" to 401,
            "invalid_dpop_proof" to 401,
            "use_dpop_nonce" to 401,
        ).forEach { (code, status) ->
            val response = MockHttpServletResponse()
            val recorded = RecordingResolver()
            SecurityProblemResponder(recorded, dpopRequired = true).commence(request("DPoP t"), response, oauthError(code))

            assertThat(response.status).describedAs(code).isEqualTo(status)
            assertThat(recorded.problems.single().statusCode.value()).describedAs(code).isEqualTo(status)
            assertThat(recorded.problems.single().headers[HttpHeaders.WWW_AUTHENTICATE].orEmpty().single()).describedAs(code).contains("error=\"$code\"")
        }
    }

    @Test
    fun `a request with no credentials is answered with a 401 that offers both schemes`() {
        responder().commence(request(), response, BadCredentialsException("no credentials"))

        assertThat(response.status).isEqualTo(401)
        assertThat(resolver.problems.single().statusCode.value()).isEqualTo(401)
        assertThat(challenges()).hasSize(2)
        assertThat(challenges()[0]).startsWith("Bearer realm=\"shortener\"").doesNotContain("error=")
        assertThat(challenges()[1]).startsWith("DPoP realm=\"shortener\"").contains("algs=\"RS256 ")
        assertThat(count(SecurityEvents.UNAUTHENTICATED)).isEqualTo(1.0)
    }

    @Test
    fun `when DPoP is required a request with no credentials is offered only DPoP`() {
        responder(dpopRequired = true).commence(request(), response, BadCredentialsException("no credentials"))

        assertThat(challenges()).singleElement().asString().startsWith("DPoP realm=")
    }

    @Test
    fun `a rejected token is answered under the scheme it used and names the error`() {
        responder().commence(request("Bearer abc"), response, oauthError("invalid_token"))

        assertThat(challenges()).singleElement().asString().startsWith("Bearer realm=").contains("error=\"invalid_token\"")
    }

    @Test
    fun `a Bearer token is still told to use DPoP when DPoP is required`() {
        responder(dpopRequired = true).commence(request("Bearer abc"), response, oauthError("invalid_token"))

        assertThat(challenges()).singleElement().asString().startsWith("DPoP realm=").contains("error=\"invalid_token\"")
    }

    @Test
    fun `a rejected proof is answered with the DPoP challenge only`() {
        responder().commence(request("DPoP abc"), response, oauthError("invalid_dpop_proof"))

        assertThat(challenges()).singleElement().asString().startsWith("DPoP realm=").contains("error=\"invalid_dpop_proof\"")
    }

    @Test
    fun `a failure is reported with its error code and never with the message of the exception`() {
        responder().commence(request("DPoP abc"), response, oauthError("invalid_token"))
        responder().commence(request("Bearer abc"), response, BadCredentialsException("secret detail"))
        responder().commence(request(), response, BadCredentialsException("secret detail"))

        assertThat(count(SecurityEvents.UNAUTHENTICATED)).isEqualTo(3.0)
        assertThat(resolver.problems.map { it.body.detail }).allSatisfy { assertThat(it).doesNotContain("detail") }
    }

    @Test
    fun `the reason of a failure with no error code is a missing or an invalid credential, by whether one was sent`() {
        LogCapture(SecurityEvents.LOGGER_NAME).use { log ->
            responder().commence(request(), response, BadCredentialsException("x"))
            responder().commence(request("Bearer abc"), response, BadCredentialsException("x"))
            responder().commence(request("DPoP abc"), response, BadCredentialsException("x"))

            assertThat(log.events.map { it.fields["event.reason"] })
                .containsExactly("missing_credentials", "invalid_credentials", "invalid_credentials")
        }
    }

    @Test
    fun `a proof that lacks the nonce is a request for it and not a failed authentication`() {
        responder().commence(request("DPoP abc"), response, oauthError(DpopNonceAuthenticationConverter.USE_DPOP_NONCE))

        assertThat(response.status).isEqualTo(401)
        assertThat(challenges()).singleElement().asString().contains("error=\"use_dpop_nonce\"")
        assertThat(resolver.problems.single().body.detail).contains("DPoP-Nonce")
        assertThat(count(SecurityEvents.NONCE_REQUESTED)).isEqualTo(1.0)
        assertThat(count(SecurityEvents.UNAUTHENTICATED)).isZero()
    }

    @Test
    fun `a denied call is a 403 that asks for the scope, and names the client when there is one`() {
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken("client-a", "n/a")

        responder().handle(request("DPoP abc"), response, AccessDeniedException("denied"))

        assertThat(response.status).isEqualTo(403)
        assertThat(challenges()).singleElement().asString().startsWith("DPoP realm=").contains("error=\"insufficient_scope\"")
        assertThat(count(SecurityEvents.FORBIDDEN)).isEqualTo(1.0)
    }

    @Test
    fun `a denied call with nobody authenticated is still a 403`() {
        responder().handle(request(), response, AccessDeniedException("denied"))

        assertThat(response.status).isEqualTo(403)
        assertThat(count(SecurityEvents.FORBIDDEN)).isEqualTo(1.0)
    }

    @Test
    fun `a limited request is a 429 that says when to retry`() {
        responder().tooManyRequests(request(), response, 7, "ip", "203.0.113.9")

        assertThat(response.status).isEqualTo(429)
        assertThat(resolver.problems.single().headers.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("7")
        assertThat(resolver.problems.single().body.detail).contains("7 seconds")
        assertThat(count(SecurityEvents.RATE_LIMITED)).isEqualTo(1.0)
    }

    @Test
    fun `a responder with no events still answers every failure`() {
        val quiet = responder(withEvents = false)

        quiet.commence(request(), response, BadCredentialsException("no credentials"))
        quiet.commence(request("DPoP abc"), response, oauthError(DpopNonceAuthenticationConverter.USE_DPOP_NONCE))
        quiet.handle(request(), response, AccessDeniedException("denied"))
        quiet.tooManyRequests(request(), response, 1)

        assertThat(resolver.problems.map { it.statusCode.value() }).containsExactly(401, 401, 403, 429)
        assertThat(meters.meters).isEmpty()
    }
}

package uy.ct.shortener.security.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import java.time.Duration
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * The small filters and the token resolver of the security chain, with no Spring context.
 *
 * - [RateLimitFilter]: what it lets through, what it answers with a `429` and how long it says to wait, and what it never limits.
 * - [DpopNonceFilter]: which responses carry the nonce.
 * - [SenderConstrainedBearerTokenResolver]: that it refuses the `Bearer` scheme and only that.
 */
class SecurityFiltersTest {

    private val problems = mutableListOf<SecurityProblem>()

    private val responder = SecurityProblemResponder(
        object : HandlerExceptionResolver {
            override fun resolveException(request: HttpServletRequest, response: HttpServletResponse, handler: Any?, ex: Exception): ModelAndView? {
                problems += assertIs<SecurityProblem>(ex)
                return null
            }
        },
        events = SecurityEvents(SimpleMeterRegistry()),
    )

    private fun request(path: String = "/api/short-links", authorization: String? = null) = MockHttpServletRequest("GET", path).apply {
        authorization?.let { addHeader(HttpHeaders.AUTHORIZATION, it) }
    }

    private fun rateLimit(capacity: Long, key: (HttpServletRequest) -> RateLimitKey) =
        RateLimitFilter("test", RateLimiter(SecurityProperties.Limit(capacity, Duration.ofMinutes(1))), responder, key)

    private fun run(filter: Filter, request: MockHttpServletRequest): Pair<MockHttpServletResponse, MockFilterChain> {
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return response to chain
    }

    private fun MockFilterChain.wasReached() = request != null

    @Test
    fun `a request within the limit is passed on and one beyond it is answered with a 429`() {
        val filter = rateLimit(2) { RateLimitKey.Of("client-a") }

        assertThat(run(filter, request()).second.wasReached()).isTrue()
        assertThat(run(filter, request()).second.wasReached()).isTrue()
        val (response, chain) = run(filter, request())

        assertThat(chain.wasReached()).isFalse()
        assertThat(response.status).isEqualTo(429)
        assertThat(problems.single().statusCode.value()).isEqualTo(429)
    }

    @Test
    fun `the wait it announces is at least a second and covers the whole refill`() {
        val filter = rateLimit(1) { RateLimitKey.Of("client-a") }
        run(filter, request())

        run(filter, request())

        val seconds = problems.single().headers.getFirst(HttpHeaders.RETRY_AFTER)!!.toLong()
        assertThat(seconds).isBetween(1L, 60L)
        assertThat(seconds).isGreaterThan(50L)
    }

    @Test
    fun `each key has its own allowance`() {
        val filter = rateLimit(1) { RateLimitKey.Of(it.remoteAddr) }

        val first = run(filter, request().apply { remoteAddr = "203.0.113.1" }).second
        val second = run(filter, request().apply { remoteAddr = "203.0.113.2" }).second

        assertThat(first.wasReached()).isTrue()
        assertThat(second.wasReached()).isTrue()
        assertThat(problems).isEmpty()
    }

    @Test
    fun `a request with no key is never limited`() {
        val filter = rateLimit(1) { RateLimitKey.Unlimited }

        repeat(5) { assertThat(run(filter, request()).second.wasReached()).isTrue() }
        assertThat(problems).isEmpty()
    }

    @Test
    fun `the actuator is never limited`() {
        val filter = rateLimit(1) { RateLimitKey.Of("client-a") }

        repeat(5) { assertThat(run(filter, request("/actuator/health")).second.wasReached()).isTrue() }
        assertThat(problems).isEmpty()
    }

    @Test
    fun `two limits with different names both apply to the same request`() {
        val byIp = RateLimitFilter("ip", RateLimiter(SecurityProperties.Limit(100)), responder) { RateLimitKey.Of("203.0.113.9") }
        val byClient = rateLimit(1) { RateLimitKey.Of("client-a") }
        val servlet = object : HttpServlet() {}

        repeat(2) {
            val response = MockHttpServletResponse()
            MockFilterChain(servlet, byIp, byClient).doFilter(request(), response)
        }

        assertThat(problems).singleElement().satisfies({ assertThat(it.statusCode.value()).isEqualTo(429) })
    }

    @Test
    fun `the nonce is handed out to a request under the DPoP scheme, whatever the case of the scheme`() {
        val nonces = HmacDpopNonces("a-secret-shared-by-every-instance".toByteArray(), Duration.ofMinutes(5))

        val (response, chain) = run(DpopNonceFilter(nonces), request(authorization = "dpop abc"))

        assertThat(response.getHeader(DpopNonceFilter.HEADER)).isEqualTo(nonces.current())
        assertThat(chain.wasReached()).isTrue()
    }

    @Test
    fun `the nonce is not handed out under another scheme or none`() {
        val nonces = HmacDpopNonces("a-secret-shared-by-every-instance".toByteArray(), Duration.ofMinutes(5))

        listOf("Bearer abc", null).forEach { authorization ->
            val (response, chain) = run(DpopNonceFilter(nonces), request(authorization = authorization))

            assertThat(response.getHeader(DpopNonceFilter.HEADER)).isNull()
            assertThat(chain.wasReached()).isTrue()
        }
    }

    @Test
    fun `no nonce is handed out when nonces are not required`() {
        val (response, chain) = run(DpopNonceFilter(NoDpopNonces), request(authorization = "DPoP abc"))

        assertThat(response.getHeader(DpopNonceFilter.HEADER)).isNull()
        assertThat(chain.wasReached()).isTrue()
    }

    @Test
    fun `the resolver refuses a Bearer token as an invalid token`() {
        val failure = assertFailsWith<OAuth2AuthenticationException> {
            SenderConstrainedBearerTokenResolver().resolve(request(authorization = "Bearer abc"))
        }

        assertThat(failure.error.errorCode).isEqualTo("invalid_token")
    }

    @Test
    fun `the resolver has nothing to say about a request with no Bearer token`() {
        val resolver = SenderConstrainedBearerTokenResolver()

        assertThat(resolver.resolve(request())).isNull()
        assertThat(resolver.resolve(request(authorization = "DPoP abc"))).isNull()
    }
}

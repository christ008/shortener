package uy.ct.shortener

import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.boot.http.client.HttpRedirects
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * What the integration tests share of `RestTestClient`, which the Spring Boot test context provides for the running server.
 * Responses are asserted with its own `expectStatus()`, `expectHeader()` and `expectBody()`; `RestTestClient` does not throw
 * on an error status.
 *
 * - [request] sends a request with the given headers and body, for a test that builds its headers (a token, a proof).
 * - [expectStatus] with a reason is `expectStatus().isEqualTo(status)` that says why that status is expected when it fails.
 * - [text] reads the body of a response that has been asserted on, for a test that needs a value out of it.
 * - [withoutRedirects] is a client for the server on [port] that hands back a redirect instead of following it. The injected
 *   client follows redirects, and `RestTestClient` cannot be derived into one that does not.
 */
object RestTestClientSupport {

    fun RestTestClient.request(method: HttpMethod, uri: String, headers: HttpHeaders = HttpHeaders(), body: Any? = null): RestTestClient.ResponseSpec {
        val request = method(method).uri(uri).headers { it.addAll(headers) }
        return (if (body == null) request else request.body(body)).exchange()
    }

    fun RestTestClient.ResponseSpec.expectStatus(why: String, status: HttpStatus): RestTestClient.ResponseSpec =
        expectStatus().value { assertThat(it).describedAs(why).isEqualTo(status.value()) }

    fun RestTestClient.ResponseSpec.text(): String = returnResult(String::class.java).responseBody.orEmpty()

    fun withoutRedirects(port: Int): RestTestClient {
        val requests = ClientHttpRequestFactoryBuilder.detect().build(HttpClientSettings.defaults().withRedirects(HttpRedirects.DONT_FOLLOW))
        return RestTestClient.bindToServer(requests).baseUrl("http://localhost:$port").build()
    }
}

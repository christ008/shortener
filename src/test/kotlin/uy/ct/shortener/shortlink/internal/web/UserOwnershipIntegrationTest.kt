package uy.ct.shortener.shortlink.internal.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import uy.ct.shortener.RestTestClientSupport.request
import uy.ct.shortener.TestIdp
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.WithTestIdp
import java.util.UUID

/**
 * People who sign in through the web UI all hold tokens issued to the same client, `shortener-ui`,
 * so the client id cannot say who owns a link. The `owner` claim does: the identity provider fills
 * it with the user id for a person and with the client id for a service. These tests act as two
 * people on one client and check that each owns, sees and may disable only their own links.
 */
@WithTestIdp
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration::class)
class UserOwnershipIntegrationTest {

    @Autowired
    lateinit var rest: RestTestClient

    private val alice = UUID.randomUUID().toString()

    private val bob = UUID.randomUUID().toString()

    private val code = "own-" + UUID.randomUUID().toString().take(8)

    private fun tokenOf(user: String) = TestIdp.token(client = "user-$user", owner = user, authorizedParty = "shortener-ui")

    private fun call(user: String, method: HttpMethod, path: String, body: String? = null): RestTestClient.ResponseSpec {
        val headers = HttpHeaders().apply {
            setBearerAuth(tokenOf(user))
            contentType = MediaType.APPLICATION_JSON
        }
        return rest.request(method, path, headers, body)
    }

    private fun disable(user: String) = call(user, HttpMethod.PATCH, "/api/short-links/$code", """{"disabled":true}""")

    private fun aliceCreatesALink() =
        call(alice, HttpMethod.PUT, "/api/short-links/$code", """{"targetUrl":"https://example.com/alices"}""")

    @Test
    fun `a person owns what they create under their user id, not under the client of the web app`() {
        aliceCreatesALink()
            .expectStatus().isCreated()
            .expectBody(String::class.java).value { assertThat(it).contains(""""createdBy":"$alice"""").doesNotContain("shortener-ui") }
    }

    @Test
    fun `another person on the same client can neither see nor disable it, and does not find it listed`() {
        aliceCreatesALink()

        call(bob, HttpMethod.GET, "/api/short-links/$code").expectStatus().isNotFound()
        disable(bob).expectStatus().isNotFound()
        call(bob, HttpMethod.GET, "/api/short-links").expectBody(String::class.java).value { assertThat(it).doesNotContain(code) }
    }

    @Test
    fun `the owner sees it listed and may disable it`() {
        aliceCreatesALink()

        call(alice, HttpMethod.GET, "/api/short-links").expectBody(String::class.java).value { assertThat(it).contains(code) }
        disable(alice).expectStatus().isOk()
    }
}

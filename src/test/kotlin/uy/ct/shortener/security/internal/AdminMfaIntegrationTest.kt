package uy.ct.shortener.security.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.client.RestTestClient
import uy.ct.shortener.RestTestClientSupport.request
import uy.ct.shortener.TestIdp
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.WithTestIdp

/**
 * The administrator scope is held by a person only with a second factor, which the API checks and does not take on trust (ADR 0031).
 * The tests send tokens as an identity provider with step-up authentication writes them: the `acr` claim is the level, `2` when the person
 * gave a one-time code and `1` when they did not.
 */
@WithTestIdp
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration::class)
class AdminMfaIntegrationTest {

    @Autowired
    lateinit var rest: RestTestClient

    @LocalServerPort
    var port: Int = 0

    private fun list(client: String, scope: String, acr: String?): RestTestClient.ResponseSpec {
        val headers = HttpHeaders().apply { setBearerAuth(TestIdp.token(client = client, scope = scope, acr = acr)) }
        return rest.request(HttpMethod.GET, "http://localhost:$port/api/short-links", headers)
    }

    private fun challenges(response: RestTestClient.ResponseSpec): List<String> =
        response.returnResult(String::class.java).responseHeaders[HttpHeaders.WWW_AUTHENTICATE].orEmpty()

    @Test
    fun `a person with the administrator scope and a second factor is let in`() {
        list("shortener-ui", "shortlinks:admin", acr = "2").expectStatus().isOk()
    }

    @Test
    fun `a person with the administrator scope and a password alone is told to sign in again with a second factor`() {
        listOf("1", "pwd", null).forEach { acr ->
            val response = list("shortener-ui", "shortlinks:admin", acr)

            response.expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
            assertThat(challenges(response)).describedAs("acr $acr").anySatisfy {
                assertThat(it).contains("error=\"insufficient_user_authentication\"").contains("acr_values=\"2\"")
            }
        }
    }

    @Test
    fun `a service account that the operator listed is an administrator without one`() {
        list("admin-client", "shortlinks:admin", acr = null).expectStatus().isOk()
    }

    @Test
    fun `the same person without the administrator scope is not asked for more`() {
        list("shortener-ui", "shortlinks:read", acr = "1").expectStatus().isOk()
        list("shortener-ui", "shortlinks:read", acr = null).expectStatus().isOk()
    }
}

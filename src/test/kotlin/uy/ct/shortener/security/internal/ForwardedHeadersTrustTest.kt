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
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import org.yaml.snakeyaml.Yaml
import uy.ct.shortener.RestTestClientSupport.request
import uy.ct.shortener.TestDpopClient
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.WithTestIdp
import java.nio.file.Files
import java.nio.file.Path

/**
 * The production stack tells the application to honour forwarded headers from the edge's subnet alone
 * (`SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` in `deploy/stack/compose.prod.yaml`). These tests run the application with that very
 * subnet, read from the file, and call it from the loopback address, which is outside it: the scheme, host and port a caller
 * claims are ignored, so a proof made for the URL it claims is refused and one made for the URL it called is accepted.
 * `DpopIntegrationTest` shows the same headers honoured from a trusted address.
 */
@WithTestIdp
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["shortener.security.dpop.required=true"],
)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration::class)
class ForwardedHeadersTrustTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun trustTheEdgesSubnetAlone(registry: DynamicPropertyRegistry) {
            val stack = Yaml().load<Map<String, Any?>>(Files.readString(Path.of("deploy/stack/compose.prod.yaml")))
            registry.add("server.tomcat.remoteip.internal-proxies") { stack.getValue("x-ingress-subnet").toString() }
        }
    }

    @Autowired
    lateinit var rest: RestTestClient

    @LocalServerPort
    var port: Int = 0

    private val client = TestDpopClient()

    private val calledUrl get() = "http://localhost:$port/api/short-links"

    private fun call(proofFor: String): RestTestClient.ResponseSpec {
        val token = client.token()
        val headers = HttpHeaders().apply {
            set(HttpHeaders.AUTHORIZATION, "DPoP $token")
            set("DPoP", client.proof("GET", proofFor, token))
            set("X-Forwarded-Host", "edge.example")
            set("X-Forwarded-Proto", "https")
            set("X-Forwarded-Port", "443")
            set("X-Forwarded-For", "203.0.113.7")
        }
        return rest.request(HttpMethod.GET, calledUrl, headers)
    }

    @Test
    fun `a caller outside the edge's subnet cannot move the URL its proof is checked against`() {
        call(proofFor = "https://edge.example/api/short-links").expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
            .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE) { assertThat(it).contains("error=\"invalid_dpop_proof\"") }
    }

    @Test
    fun `its proof for the URL it called is accepted, whatever it claims in the forwarded headers`() {
        call(proofFor = calledUrl).expectStatus().isOk()
    }
}

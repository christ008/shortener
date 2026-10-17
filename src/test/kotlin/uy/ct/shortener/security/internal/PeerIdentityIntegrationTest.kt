package uy.ct.shortener.security.internal

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.WithTestIdp

/**
 * With an allowed identity configured, the application serves only callers whose TLS certificate names it, and the management port,
 * which has no TLS and no certificate, still answers its health probes.
 */
@WithTestIdp
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["shortener.security.mtls.allowed-identities=spiffe://shortener.internal/prod/edge"],
)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration::class)
class PeerIdentityIntegrationTest {

    @Autowired
    lateinit var rest: RestTestClient

    @LocalManagementPort
    var managementPort: Int = 0

    @Test
    fun `a caller with no client certificate is a 403, and the health probes of the management port are still served`() {
        rest.get().uri("/zzzzzzz").exchange()
            .expectStatus().isForbidden()
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
        rest.get().uri("http://localhost:$managementPort/actuator/health/readiness").exchange().expectStatus().isOk()
    }
}

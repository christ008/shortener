package uy.ct.shortener.security.internal

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.WithTestIdp

/**
 * All requests are limited per client IP, so redirects and token guessing are throttled, but
 * actuator endpoints never are, so health checks cannot be starved.
 */
@WithTestIdp
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["shortener.security.rate-limit.per-ip.capacity=5"],
)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration::class)
class IpRateLimitIntegrationTest {

    @Autowired
    lateinit var rest: RestTestClient

    @LocalManagementPort
    var managementPort: Int = 0

    @Test
    fun `limits requests per client ip but never actuator endpoints`() {
        repeat(5) { rest.get().uri("/zzzzzzz").exchange().expectStatus().isNotFound() }

        rest.get().uri("/zzzzzzz").exchange()
            .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectHeader().exists(HttpHeaders.RETRY_AFTER)
        rest.get().uri("http://localhost:$managementPort/actuator/health").exchange().expectStatus().isOk()
    }
}

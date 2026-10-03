package uy.ct.shortener.security.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.WithTestIdp

/**
 * All requests are limited per client IP, so redirects and token guessing are throttled, but
 * actuator endpoints never are, so the kubelet's probes cannot be starved.
 */
@WithTestIdp
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["shortener.security.rate-limit.per-ip.capacity=5"],
)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration::class)
class IpRateLimitIntegrationTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @LocalManagementPort
    var managementPort: Int = 0

    @Test
    fun `limits requests per client ip but never actuator endpoints`() {
        repeat(5) { assertThat(restTemplate.getForEntity("/zzzzzzz", String::class.java).statusCode).isEqualTo(HttpStatus.NOT_FOUND) }

        val limited = restTemplate.getForEntity("/zzzzzzz", String::class.java)

        assertThat(limited.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(limited.headers.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(limited.headers.getFirst(HttpHeaders.RETRY_AFTER)).isNotNull
        assertThat(restTemplate.getForEntity("http://localhost:$managementPort/actuator/health", String::class.java).statusCode).isEqualTo(HttpStatus.OK)
    }
}

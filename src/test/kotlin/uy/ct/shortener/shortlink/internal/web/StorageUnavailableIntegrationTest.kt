package uy.ct.shortener.shortlink.internal.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import uy.ct.shortener.TestIdp
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.WithTestIdp
import javax.sql.DataSource

/**
 * When no database connection can be had, requests are answered with a 503 problem detail and
 * `Retry-After` instead of a 500, and the service recovers on its own once a connection is free.
 * The pool is shrunk to one connection with a short timeout, and the test holds that connection.
 */
@WithTestIdp
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.connection-timeout=250",
    ],
)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration::class)
class StorageUnavailableIntegrationTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var dataSource: DataSource

    private fun create() = restTemplate.exchange(
        "/api/short-links",
        HttpMethod.POST,
        HttpEntity(
            """{"targetUrl":"https://example.com/unavailable"}""",
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                setBearerAuth(TestIdp.token())
            },
        ),
        String::class.java,
    )

    @Test
    fun `answers 503 with Retry-After while every connection is busy, then recovers`() {
        dataSource.connection.use {
            val lookup = restTemplate.getForEntity("/zzzzzzz", String::class.java)

            assertThat(lookup.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            assertThat(lookup.headers.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
            assertThat(lookup.headers.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5")
            assertThat(lookup.body).contains(""""status":503""")

            val creation = create()

            assertThat(creation.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            assertThat(creation.headers.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5")
        }

        assertThat(restTemplate.getForEntity("/zzzzzzz", String::class.java).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(create().statusCode).isEqualTo(HttpStatus.CREATED)
    }
}

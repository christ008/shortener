package uy.ct.shortener

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.client.RestTestClient
import uy.ct.shortener.RestTestClientSupport.request

/**
 * What an operator actually reads in production: the JSON the `production` log format writes. A security event must come
 * out as one line with its fields, and the token that failed must not.
 */
@WithTestIdp
@ExtendWith(OutputCaptureExtension::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["logging.structured.format.console=ecs"])
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration::class)
class StructuredSecurityLogTest {

    @Autowired
    lateinit var rest: RestTestClient

    @Test
    fun `a rejected token is one JSON line with its fields and its trace, and without the token`(output: CapturedOutput) {
        val headers = HttpHeaders().apply { set(HttpHeaders.AUTHORIZATION, "Bearer a.SECRET-TOKEN.c") }
        rest.request(HttpMethod.GET, "/api/short-links", headers)

        val line = output.all.lines().single { it.contains("Authentication failed") }
        assertThat(line).startsWith("{").contains(
            "\"event\":{\"category\":\"security\",\"action\":\"unauthenticated\",\"reason\":\"invalid_token\"}",
            "\"client\":{\"ip\":",
            "\"traceId\":",
        )
        assertThat(output.all).doesNotContain("SECRET-TOKEN")
    }
}

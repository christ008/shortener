package uy.ct.shortener

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Requests, on both the application and the management port, must be served on virtual threads.
 * A test-only filter records the thread of each application request; the database call a request
 * makes runs on that same thread, so this covers the whole request path. The management port is a
 * separate server that a filter in this context never sees, so a test-only health indicator
 * records the thread it is evaluated on instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [TestApiKeys.PROPERTY])
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration::class, VirtualThreadsIntegrationTest.ThreadRecorder::class)
class VirtualThreadsIntegrationTest {

    class RecordingFilter : OncePerRequestFilter() {
        val seen = CopyOnWriteArrayList<Pair<String, Boolean>>()

        override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
            seen += request.requestURI to Thread.currentThread().isVirtual
            chain.doFilter(request, response)
        }
    }

    class ThreadRecordingHealthIndicator : HealthIndicator {
        val seen = CopyOnWriteArrayList<Boolean>()

        override fun health(): Health {
            seen += Thread.currentThread().isVirtual
            return Health.up().build()
        }
    }

    @TestConfiguration
    class ThreadRecorder {
        @Bean
        fun recordingFilter() = RecordingFilter()

        @Bean
        fun threadRecordingHealthIndicator() = ThreadRecordingHealthIndicator()
    }

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var recorder: RecordingFilter

    @Autowired
    lateinit var healthRecorder: ThreadRecordingHealthIndicator

    @LocalManagementPort
    var managementPort: Int = 0

    @Test
    fun `serves requests on virtual threads on both ports`() {
        restTemplate.getForEntity("/zzzzzzz", String::class.java)
        restTemplate.getForEntity("http://localhost:$managementPort/actuator/health", String::class.java)

        assertThat(recorder.seen.map { it.first }).contains("/zzzzzzz")
        assertThat(recorder.seen.filter { !it.second }).describedAs("application requests on platform threads").isEmpty()
        assertThat(healthRecorder.seen).describedAs("health checks on the management port").isNotEmpty.doesNotContain(false)
    }
}

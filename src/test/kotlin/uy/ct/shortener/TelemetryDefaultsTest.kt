package uy.ct.shortener

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment

/**
 * What the application sends out on its own. Traces are not sampled until a rate is set, so
 * running without a collector exports nothing and logs no errors. The exporter itself stays built
 * in and the endpoint has a default, because for a native image Spring decides at build time which
 * beans exist and an exporter without an endpoint when the image is built is left out for good. The OTLP exporters for
 * logs and metrics are off, since Prometheus already scrapes the metrics. Spring Security's
 * per-filter observations are off: each would add a span for every filter on every request.
 */
@WithTestIdp
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class TelemetryDefaultsTest {

    @Autowired
    lateinit var environment: Environment

    private fun property(name: String) = environment.getProperty(name)

    @Test
    fun `samples no traces until a rate is set`() {
        assertThat(property("management.tracing.sampling.probability")).isEqualTo("0.0")
    }

    @Test
    fun `names a default OTLP endpoint, because a native image only includes the exporter if one is set when it is built`() {
        assertThat(property("management.opentelemetry.tracing.export.otlp.endpoint")).isEqualTo("http://localhost:4318/v1/traces")
    }

    @Test
    fun `leaves the other OTLP exporters and the per-filter security spans off`() {
        assertThat(property("management.otlp.metrics.export.enabled")).isEqualTo("false")
        assertThat(property("management.logging.export.otlp.enabled")).isEqualTo("false")
        assertThat(property("management.observations.enable.spring.security")).isEqualTo("false")
    }
}

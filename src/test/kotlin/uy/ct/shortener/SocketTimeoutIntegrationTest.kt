package uy.ct.shortener

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertFailsWith

/**
 * The PostgreSQL driver reads `socketTimeout` by its exact, case sensitive name and silently ignores any other
 * spelling, which would leave a query to hang for as long as the network stays silent. With the limit lowered to one
 * second, a three second statement has to fail rather than finish: that shows the property declared in
 * `application.yaml` is spelled the way the driver expects and that a deployment can override it (Spring keeps the
 * YAML spelling when an environment variable, which can only be lowercase, sets the value). A failure to read is
 * reported as [DataAccessResourceFailureException], which the repository turns into a 503.
 */
@WithTestIdp
@SpringBootTest(properties = ["spring.datasource.hikari.data-source-properties.socketTimeout=1"])
@Import(TestcontainersConfiguration::class)
class SocketTimeoutIntegrationTest {

    @Autowired
    lateinit var jdbc: JdbcClient

    @Test
    fun `gives up on a database that stays silent for longer than the socket timeout`() {
        val started = System.nanoTime()

        assertFailsWith<DataAccessResourceFailureException> { jdbc.sql("SELECT pg_sleep(3)").query().singleRow() }

        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_500)
    }
}

package uy.ct.shortener

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * How the application holds its database connections. The pool keeps idle connections alive, because a load
 * balancer or NAT in the path drops silent ones and the first request after that would fail, and reports a
 * connection held for more than 10 s, which is a leak or a statement that should have been cut off. Every connection
 * carries the application name and host, so `pg_stat_activity` says who holds what, and the driver does not wait forever
 * to connect or for an answer (the next test shows the answer timeout reaching the driver).
 */
@WithTestIdp
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class DatasourceHardeningTest {

    @Autowired
    lateinit var pool: HikariDataSource

    @Autowired
    lateinit var jdbc: JdbcClient

    @Test
    fun `names the pool and keeps idle connections alive`() {
        assertThat(pool.poolName).isEqualTo("shortener")
        assertThat(pool.keepaliveTime).isEqualTo(120_000)
        assertThat(pool.leakDetectionThreshold).isEqualTo(10_000)
    }

    @Test
    fun `tells Postgres which application and host each connection belongs to`() {
        val applicationName = jdbc.sql("SHOW application_name").query(String::class.java).single()

        assertThat(applicationName).startsWith("shortener-")
    }

    @Test
    fun `bounds how long the driver waits to connect and keeps TCP alive`() {
        assertThat(pool.dataSourceProperties)
            .containsEntry("connectTimeout", "3")
            .containsEntry("socketTimeout", "15")
            .containsEntry("tcpKeepAlive", "true")
    }
}

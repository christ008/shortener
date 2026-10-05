package uy.ct.shortener.shortlink.internal.persistence

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.dao.QueryTimeoutException
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.StorageUnavailableException
import javax.sql.DataSource
import kotlin.test.assertFailsWith

/**
 * The application role is cut off by the database itself: a statement is cancelled after 5 s and a lock not obtained in
 * 2 s is given up (`deploy/postgres/bootstrap.sql`). What a client sees depends on how Spring translates the errors.
 * Postgres reports `57014`, which Spring turns into [QueryTimeoutException], and `55P03`, which it leaves as an
 * [UncategorizedSQLException]; neither is the resource failure used for a database that cannot be reached, so unless
 * the repository maps them too they reach the client as a 500 instead of a 503 with `Retry-After`. Another connection
 * holds an exclusive lock on the table, which makes both limits fire deterministically on the repository's own query.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@JdbcTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(TestcontainersConfiguration::class)
class DatabaseLimitsTest {

    @Autowired
    lateinit var dataSource: DataSource

    private fun <T> whileTableIsLocked(session: String, block: (JdbcShortLinkRepository) -> T): T {
        val pool = dataSource.unwrap(HikariDataSource::class.java)
        val holder = pool.connection.apply { autoCommit = false }
        try {
            holder.createStatement().execute("LOCK TABLE short_link IN ACCESS EXCLUSIVE MODE")
            val client = SingleConnectionDataSource(pool.jdbcUrl, pool.username, pool.password ?: "", true)
            try {
                client.connection.createStatement().execute(session)
                return block(JdbcShortLinkRepository(JdbcClient.create(client)))
            } finally {
                client.destroy()
            }
        } finally {
            holder.rollback()
            holder.close()
        }
    }

    @Test
    fun `reports storage unavailable when the database cuts a statement off`() {
        val failure = whileTableIsLocked("SET statement_timeout = '200ms'") { repository ->
            assertFailsWith<StorageUnavailableException> { repository.findByShortCode(ShortCode("abcdefg")) }
        }

        assertThat(failure.cause).isInstanceOf(QueryTimeoutException::class.java).hasMessageContaining("statement timeout")
    }

    @Test
    fun `reports storage unavailable when a lock is not obtained in time`() {
        val failure = whileTableIsLocked("SET lock_timeout = '200ms'") { repository ->
            assertFailsWith<StorageUnavailableException> { repository.findByShortCode(ShortCode("abcdefg")) }
        }

        assertThat(failure.cause).isInstanceOf(UncategorizedSQLException::class.java).hasMessageContaining("lock timeout")
    }
}

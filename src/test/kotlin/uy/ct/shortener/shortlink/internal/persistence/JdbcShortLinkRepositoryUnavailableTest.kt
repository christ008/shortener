package uy.ct.shortener.shortlink.internal.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.AbstractDataSource
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.StorageUnavailableException
import java.lang.reflect.Proxy
import java.net.URI
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import kotlin.test.assertFailsWith

/**
 * A repository whose pool cannot hand out a connection reports [StorageUnavailableException] from
 * both operations, keeping the underlying failure as the cause, and does not leak Spring's
 * data-access types. Needs no database.
 *
 * The pool and the connection are small hand-written fakes, not mocks of the JDBC interfaces: each does one thing, fail
 * in the way the test names, and the repository sees exactly what a real driver would throw.
 */
class JdbcShortLinkRepositoryUnavailableTest {

    /** A pool with no connection to give, as HikariCP reports it when every connection is in use past the timeout. */
    private class ExhaustedPool : AbstractDataSource() {
        override fun getConnection(): Connection = throw SQLTransientConnectionException("Connection is not available")

        override fun getConnection(username: String?, password: String?): Connection = getConnection()
    }

    /** A pool whose connections refuse every statement with [failure], and answer `false` or `0` to anything else. */
    private class RefusingPool(private val failure: SQLException) : AbstractDataSource() {
        private val refusing = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when {
                method.name == "prepareStatement" -> throw failure
                method.returnType == java.lang.Boolean.TYPE -> false
                method.returnType == Integer.TYPE -> 0
                else -> null
            }
        } as Connection

        override fun getConnection(): Connection = refusing

        override fun getConnection(username: String?, password: String?): Connection = getConnection()
    }

    private val repository = JdbcShortLinkRepository(JdbcClient.create(ExhaustedPool()))

    @Test
    fun `reports storage unavailable when a lookup cannot get a connection`() {
        val failure = assertFailsWith<StorageUnavailableException> { repository.findByShortCode(ShortCode("abcdefg")) }

        assertThat(failure.cause).isInstanceOf(DataAccessResourceFailureException::class.java)
    }

    @Test
    fun `reports storage unavailable when an insert cannot get a connection`() {
        assertFailsWith<StorageUnavailableException> {
            repository.insertIfAbsent(ShortCode("abcdefg"), URI.create("https://example.com"), "ci")
        }
    }

    @Test
    fun `does not pass off another database error as storage being unavailable`() {
        val pool = RefusingPool(SQLException("internal error", "XX000"))

        assertFailsWith<UncategorizedSQLException> { JdbcShortLinkRepository(JdbcClient.create(pool)).findByShortCode(ShortCode("abcdefg")) }
    }

    @Test
    fun `asks clients to retry after a few seconds`() {
        val failure = StorageUnavailableException(RuntimeException("boom"))

        assertThat(failure.headers.getFirst("Retry-After")).isEqualTo(StorageUnavailableException.RETRY_AFTER_SECONDS.toString())
        assertThat(failure.statusCode.value()).isEqualTo(503)
    }
}

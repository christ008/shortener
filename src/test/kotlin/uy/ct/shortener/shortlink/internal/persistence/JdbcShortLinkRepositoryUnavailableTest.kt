package uy.ct.shortener.shortlink.internal.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.simple.JdbcClient
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.StorageUnavailableException
import java.net.URI
import java.sql.SQLTransientConnectionException
import javax.sql.DataSource
import kotlin.test.assertFailsWith

/**
 * A repository whose pool cannot hand out a connection reports [StorageUnavailableException] from
 * both operations, keeping the underlying failure as the cause, and does not leak Spring's
 * data-access types. Needs no database.
 */
class JdbcShortLinkRepositoryUnavailableTest {

    private val exhaustedPool = Mockito.mock(DataSource::class.java).also {
        Mockito.`when`(it.connection).thenThrow(SQLTransientConnectionException("Connection is not available"))
    }

    private val repository = JdbcShortLinkRepository(JdbcClient.create(exhaustedPool))

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
    fun `asks clients to retry after a few seconds`() {
        val failure = StorageUnavailableException(RuntimeException("boom"))

        assertThat(failure.headers.getFirst("Retry-After")).isEqualTo(StorageUnavailableException.RETRY_AFTER_SECONDS.toString())
        assertThat(failure.statusCode.value()).isEqualTo(503)
    }
}

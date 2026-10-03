package uy.ct.shortener.shortlink.internal.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.context.annotation.Import
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.shortlink.ShortCode
import java.net.URI
import java.time.Duration
import java.time.Instant

/**
 * [JdbcShortLinkRepository] against real Postgres with the Flyway schema applied. `replace =
 * NONE` stops `@JdbcTest` from swapping in an embedded database.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(TestcontainersConfiguration::class, JdbcShortLinkRepository::class)
class JdbcShortLinkRepositoryTest {

    @Autowired
    lateinit var repository: JdbcShortLinkRepository

    @Test
    fun `stores a link and finds it by its code`() {
        val stored = repository.insertIfAbsent(ShortCode("aB3xQ9z"), URI.create("https://example.com/a/b/c?q=1#frag"), "ci")

        val found = repository.findByShortCode(ShortCode("aB3xQ9z"))

        assertThat(stored).isNotNull
        assertThat(found).isEqualTo(stored)
        assertThat(found!!.targetUrl).isEqualTo(URI.create("https://example.com/a/b/c?q=1#frag"))
        assertThat(found.createdBy).isEqualTo("ci")
    }

    @Test
    fun `lets the database assign the creation time`() {
        val stored = repository.insertIfAbsent(ShortCode("timeAAA"), URI.create("https://example.com"), "ci")!!

        assertThat(Duration.between(stored.createdAt, Instant.now()).abs()).isLessThan(Duration.ofSeconds(30))
    }

    @Test
    fun `returns null instead of overwriting when the code is taken`() {
        repository.insertIfAbsent(ShortCode("takenXx"), URI.create("https://example.com/first"), "first")

        val second = repository.insertIfAbsent(ShortCode("takenXx"), URI.create("https://example.com/second"), "second")

        assertThat(second).isNull()
        assertThat(repository.findByShortCode(ShortCode("takenXx"))!!.targetUrl).isEqualTo(URI.create("https://example.com/first"))
    }

    @Test
    fun `finds nothing for an unknown code`() {
        assertThat(repository.findByShortCode(ShortCode("nothere"))).isNull()
    }
}

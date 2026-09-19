package uy.ct.shortener.shortlink

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import uy.ct.shortener.TestcontainersConfiguration
import java.net.URI
import kotlin.test.assertFailsWith

/** `replace = NONE` keeps the Testcontainers-backed datasource instead of `@DataJpaTest`'s default embedded swap-in. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(TestcontainersConfiguration::class)
class ShortLinkRepositoryTest {

    @Autowired
    lateinit var repository: ShortLinkRepository

    @Test
    fun `persists a short link and finds it by its code`() {
        val saved = repository.saveAndFlush(
            ShortLink(shortCode = ShortCode("aB3xQ9z"), targetUrl = URI.create("https://example.com/a/b/c"))
        )

        val found = repository.findByShortCode(ShortCode("aB3xQ9z"))

        assertThat(found).isNotNull
        assertThat(found?.id).isEqualTo(saved.id)
        assertThat(found?.targetUrl).isEqualTo(URI.create("https://example.com/a/b/c"))
    }

    @Test
    fun `reports whether a short code is already taken`() {
        repository.saveAndFlush(ShortLink(shortCode = ShortCode("takenXx"), targetUrl = URI.create("https://example.com")))

        assertThat(repository.existsByShortCode(ShortCode("takenXx"))).isTrue
        assertThat(repository.existsByShortCode(ShortCode("freeYyz"))).isFalse
    }

    @Test
    fun `rejects a duplicate short code at the database level`() {
        repository.saveAndFlush(ShortLink(shortCode = ShortCode("dupCode"), targetUrl = URI.create("https://example.com/a")))

        assertFailsWith<DataIntegrityViolationException> {
            repository.saveAndFlush(ShortLink(shortCode = ShortCode("dupCode"), targetUrl = URI.create("https://example.com/b")))
        }
    }
}

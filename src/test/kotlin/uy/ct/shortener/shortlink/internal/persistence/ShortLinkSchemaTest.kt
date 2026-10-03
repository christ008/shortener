package uy.ct.shortener.shortlink.internal.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import java.net.URI
import kotlin.test.assertFailsWith

/**
 * The `short_link` constraints must accept and reject exactly what [ShortCode] and [ShortLink]
 * do, so a bad value cannot get in through any path other than this application. It runs
 * without a surrounding transaction because on Postgres a rejected statement aborts the
 * transaction it is in, which would break every later check.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@JdbcTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(TestcontainersConfiguration::class)
class ShortLinkSchemaTest {

    @Autowired
    lateinit var jdbc: JdbcClient

    private fun insert(code: String, url: String) =
        jdbc.sql("INSERT INTO short_link (short_code, target_url) VALUES (:code, :url)")
            .param("code", code).param("url", url).update()

    @Test
    fun `accepts the short codes ShortCode accepts and rejects the ones it rejects`() {
        listOf("abc", "aB3xQ9z", "my-promo_2026", "A".repeat(32)).forEach { assertThat(insert(it, "https://example.com")).isEqualTo(1) }

        listOf("", "ab", "a".repeat(33), "has space", "dot.ted", "slash/es", "ünï", "abc\n").forEach {
            assertFailsWith<DataIntegrityViolationException>("expected '$it' to be rejected") { insert(it, "https://example.com") }
            assertFailsWith<IllegalArgumentException> { ShortCode(it) }
        }
    }

    @Test
    fun `accepts the target urls ShortLink accepts and rejects the ones it rejects`() {
        listOf("http://example.com", "https://example.com/a?b=c#d", "HTTPS://EXAMPLE.COM").forEachIndexed { i, url ->
            assertThat(insert("okurl$i", url)).isEqualTo(1)
        }

        listOf("ftp://example.com/file", "mailto:someone@example.com", "/relative/path", "example.com").forEachIndexed { i, url ->
            assertFailsWith<DataIntegrityViolationException>("expected '$url' to be rejected") { insert("badurl$i", url) }
            assertFailsWith<IllegalArgumentException> { ShortLink.requireValidTargetUrl(URI.create(url)) }
        }
    }

    @Test
    fun `makes the short code the primary key`() {
        insert("samecode", "https://example.com/1")

        assertFailsWith<DataIntegrityViolationException> { insert("samecode", "https://example.com/2") }
    }

    @Test
    fun `records who disabled a link together with when, never one without the other`() {
        insert("both-set", "https://example.com")

        assertFailsWith<DataIntegrityViolationException> {
            jdbc.sql("UPDATE short_link SET disabled_at = now() WHERE short_code = 'both-set'").update()
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.sql("UPDATE short_link SET disabled_by = 'someone' WHERE short_code = 'both-set'").update()
        }
        assertThat(
            jdbc.sql("UPDATE short_link SET disabled_at = now(), disabled_by = 'someone' WHERE short_code = 'both-set'").update(),
        ).isEqualTo(1)
    }

    @Test
    fun `has the indexes the listings are served from`() {
        val indexes = jdbc.sql("SELECT indexname FROM pg_indexes WHERE tablename = 'short_link'").query(String::class.java).list()

        assertThat(indexes).contains("short_link_pkey", "short_link_created_at_idx", "short_link_created_by_idx")
    }
}

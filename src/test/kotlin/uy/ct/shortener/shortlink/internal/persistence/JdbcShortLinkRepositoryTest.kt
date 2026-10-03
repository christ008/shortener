package uy.ct.shortener.shortlink.internal.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.shortlink.InvalidSortException
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlin.test.assertFailsWith

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

    @Test
    fun `lists newest first in pages that neither overlap nor skip, optionally only one creator's links`() {
        val mine = (1..5).map { repository.insertIfAbsent(ShortCode("pg0000$it"), URI.create("https://example.com/$it"), "pager")!! }
        repository.insertIfAbsent(ShortCode("pgother1"), URI.create("https://example.com/other"), "someone-else")
        val newestFirst = compareByDescending<ShortLink> { it.createdAt }.thenByDescending { it.shortCode.value }

        val seen = mutableListOf<ShortLink>()
        var page = 0
        do {
            val slice = repository.list("pager", PageRequest.of(page++, 2))
            assertThat(slice.content.size).isLessThanOrEqualTo(2)
            seen += slice.content
        } while (slice.hasNext())

        assertThat(seen).containsExactlyElementsOf(mine.sortedWith(newestFirst))
        assertThat(repository.list(null, PageRequest.of(0, 100)).content.map { it.shortCode }).contains(ShortCode("pgother1"))
    }

    @Test
    fun `says another page follows only when one does, without counting`() {
        listOf("ex00001", "ex00002").forEach { repository.insertIfAbsent(ShortCode(it), URI.create("https://example.com"), "exact") }

        assertThat(repository.list("exact", PageRequest.of(0, 2)).hasNext()).isFalse
        assertThat(repository.list("exact", PageRequest.of(0, 1)).hasNext()).isTrue
        assertThat(repository.list("exact", PageRequest.of(5, 2)).content).isEmpty()
    }

    @Test
    fun `sorts by an allowed property in the requested direction, breaking ties by short code`() {
        listOf("so00003", "so00001", "so00002").forEach { repository.insertIfAbsent(ShortCode(it), URI.create("https://example.com"), "sorter") }

        val ascending = repository.list("sorter", PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "shortCode")))
        val descending = repository.list("sorter", PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "shortCode")))

        assertThat(ascending.content.map { it.shortCode.value }).containsExactly("so00001", "so00002", "so00003")
        assertThat(descending.content.map { it.shortCode.value }).containsExactly("so00003", "so00002", "so00001")
    }

    @Test
    fun `refuses to sort by a property links cannot be listed by, instead of passing it to the database`() {
        val failure = assertFailsWith<InvalidSortException> {
            repository.list(null, PageRequest.of(0, 10, Sort.by("targetUrl; DROP TABLE short_link")))
        }

        assertThat(failure.body.detail).contains("createdAt", "shortCode")
        assertThat(repository.list(null, PageRequest.of(0, 1)).content).isNotNull
    }

    @Test
    fun `refuses an unpaged request, which would load every link`() {
        assertFailsWith<IllegalArgumentException> { repository.list(null, Pageable.unpaged()) }
    }

    @Test
    fun `disables a link once, keeping who disabled it and when`() {
        repository.insertIfAbsent(ShortCode("off0001"), URI.create("https://example.com"), "owner")

        val first = repository.disable(ShortCode("off0001"), "first")!!
        val second = repository.disable(ShortCode("off0001"), "second")!!

        assertThat(first.isDisabled).isTrue
        assertThat(first.disabledBy).isEqualTo("first")
        assertThat(second).isEqualTo(first)
        assertThat(repository.findByShortCode(ShortCode("off0001"))).isEqualTo(first)
    }

    @Test
    fun `has nothing to disable for an unknown code`() {
        assertThat(repository.disable(ShortCode("nothere"), "someone")).isNull()
    }
}

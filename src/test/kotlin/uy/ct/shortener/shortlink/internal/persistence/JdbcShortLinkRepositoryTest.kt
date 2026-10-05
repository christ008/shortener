package uy.ct.shortener.shortlink.internal.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.shortlink.LinkCursor
import uy.ct.shortener.shortlink.LinkOrder
import uy.ct.shortener.shortlink.LinkPage
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLinkRepository
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

    @Autowired
    lateinit var jdbc: JdbcClient

    /** Inserts with a creation time of the test's choosing; in a test transaction `now()` is the same for every link. */
    private fun insertAt(code: String, createdAt: String, createdBy: String = "pager") {
        jdbc.sql("INSERT INTO short_link (short_code, target_url, created_by, created_at) VALUES (:code, 'https://example.com', :by, CAST(:at AS timestamptz))")
            .param("code", code).param("by", createdBy).param("at", createdAt).update()
    }

    private fun pages(createdBy: String?, order: LinkOrder, size: Int): List<LinkPage> {
        val pages = mutableListOf<LinkPage>()
        var after: LinkCursor? = null
        do {
            val page = repository.list(createdBy, order, size, after)
            pages += page
            after = page.next
            check(pages.size < 100) { "the listing does not end" }
        } while (after != null)
        return pages
    }

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
    fun `lists newest first, a page at a time, without repeating or skipping a link, and only one creator's if asked`() {
        val times = listOf("2026-03-01 10:00:00+00", "2026-03-01 10:00:07+00", "2026-03-02 00:00:00+00", "2026-02-27 23:59:59+00", "2026-03-05 12:00:00+00", "2026-01-01 00:00:00+00", "2026-03-03 08:30:00+00")
        times.forEachIndexed { i, at -> insertAt("pg0000$i", at) }
        insertAt("pgother1", "2026-03-04 00:00:00+00", "someone-else")

        val pages = pages("pager", LinkOrder.NEWEST_FIRST, 3)

        assertThat(pages.map { it.items.size }).containsExactly(3, 3, 1)
        assertThat(pages.flatMap { it.items }.map { it.shortCode.value })
            .containsExactly("pg00004", "pg00006", "pg00002", "pg00001", "pg00000", "pg00003", "pg00005")
        assertThat(repository.list(null, LinkOrder.NEWEST_FIRST, 100, null).items.map { it.shortCode }).contains(ShortCode("pgother1"))
    }

    @Test
    fun `lists oldest first from the other end by the same means`() {
        listOf("2026-03-01 10:00:00+00", "2026-03-05 12:00:00+00", "2026-01-01 00:00:00+00", "2026-03-03 08:30:00+00", "2026-02-02 02:02:02+00")
            .forEachIndexed { i, at -> insertAt("ol0000$i", at) }

        val pages = pages("pager", LinkOrder.OLDEST_FIRST, 2)

        assertThat(pages.map { it.items.size }).containsExactly(2, 2, 1)
        assertThat(pages.flatMap { it.items }.map { it.shortCode.value }).containsExactly("ol00002", "ol00004", "ol00000", "ol00003", "ol00001")
    }

    @Test
    fun `breaks ties between links created at the same instant by short code in byte order, so pages never overlap`() {
        val codes = listOf("aa00001", "Zz00001", "mm00001", "Aa00001", "zz00001", "00000aa")
        codes.forEach { insertAt(it, "2026-04-01 00:00:00+00") }
        val inBytes = codes.sorted()

        assertThat(pages("pager", LinkOrder.NEWEST_FIRST, 2).flatMap { it.items }.map { it.shortCode.value }).containsExactlyElementsOf(inBytes.reversed())
        assertThat(pages("pager", LinkOrder.OLDEST_FIRST, 2).flatMap { it.items }.map { it.shortCode.value }).containsExactlyElementsOf(inBytes)
    }

    @Test
    fun `says another page follows only when one does, and never leads to an empty page`() {
        listOf("ex00001", "ex00002").forEach { repository.insertIfAbsent(ShortCode(it), URI.create("https://example.com"), "exact") }

        val whole = repository.list("exact", LinkOrder.NEWEST_FIRST, 2, null)
        val half = repository.list("exact", LinkOrder.NEWEST_FIRST, 1, null)

        assertThat(whole.items).hasSize(2)
        assertThat(whole.next).isNull()
        assertThat(half.next).isEqualTo(LinkCursor(half.items.single().createdAt, half.items.single().shortCode))
        val last = repository.list("exact", LinkOrder.NEWEST_FIRST, 1, half.next)
        assertThat(last.items).hasSize(1)
        assertThat(last.next).isNull()
        assertThat(repository.list("exact", LinkOrder.NEWEST_FIRST, 5, last.items.single().let { LinkCursor(it.createdAt, it.shortCode) }).items).isEmpty()
    }

    @Test
    fun `is not shifted by a link created between two pages, which an offset would be`() {
        listOf("2026-05-01 00:00:00+00", "2026-05-02 00:00:00+00", "2026-05-03 00:00:00+00", "2026-05-04 00:00:00+00")
            .forEachIndexed { i, at -> insertAt("st0000$i", at) }
        val first = repository.list("pager", LinkOrder.NEWEST_FIRST, 2, null)

        insertAt("stnewer", "2026-06-01 00:00:00+00")
        val second = repository.list("pager", LinkOrder.NEWEST_FIRST, 2, first.next)

        assertThat(first.items.map { it.shortCode.value }).containsExactly("st00003", "st00002")
        assertThat(second.items.map { it.shortCode.value }).containsExactly("st00001", "st00000")
        assertThat(repository.list("pager", LinkOrder.NEWEST_FIRST, 1, null).items.single().shortCode.value).isEqualTo("stnewer")
    }

    @Test
    fun `keeps the microsecond a link was created in when it is the last of a page`() {
        insertAt("us00001", "2026-07-01 00:00:00.000001+00")
        insertAt("us00002", "2026-07-01 00:00:00.000002+00")
        insertAt("us00003", "2026-07-01 00:00:00.000003+00")

        assertThat(pages("pager", LinkOrder.NEWEST_FIRST, 1).flatMap { it.items }.map { it.shortCode.value }).containsExactly("us00003", "us00002", "us00001")
        assertThat(pages("pager", LinkOrder.OLDEST_FIRST, 1).flatMap { it.items }.map { it.shortCode.value }).containsExactly("us00001", "us00002", "us00003")
    }

    @Test
    fun `refuses a page of no links, or of more than the maximum, which would load every link`() {
        assertFailsWith<IllegalArgumentException> { repository.list(null, LinkOrder.NEWEST_FIRST, 0, null) }
        assertFailsWith<IllegalArgumentException> { repository.list(null, LinkOrder.NEWEST_FIRST, ShortLinkRepository.MAX_PAGE_SIZE + 1, null) }
        assertThat(repository.list(null, LinkOrder.NEWEST_FIRST, ShortLinkRepository.MAX_PAGE_SIZE, null).items).isNotNull
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

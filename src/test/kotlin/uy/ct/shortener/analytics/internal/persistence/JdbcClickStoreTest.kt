package uy.ct.shortener.analytics.internal.persistence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.analytics.internal.ClickCount
import uy.ct.shortener.analytics.internal.ClickKey
import uy.ct.shortener.analytics.internal.DeviceClass
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.assertFailsWith

/**
 * [JdbcClickStore] against real Postgres with the Flyway schema applied: a batch becomes rows, and a count added to a key that
 * exists adds up to what is there, in the same batch or in a later one.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(TestcontainersConfiguration::class, JdbcClickStore::class)
class JdbcClickStoreTest {

    @Autowired
    lateinit var store: JdbcClickStore

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private val hour = Instant.parse("2026-10-12T10:00:00Z").epochSecond / 3600

    private fun count(code: String, clicks: Long, device: DeviceClass = DeviceClass.DESKTOP, at: Long = hour) = ClickCount(ClickKey(code, at, device), clicks)

    private fun rows() = jdbc.queryForList("SELECT short_code, extract(epoch FROM hour)::bigint AS epoch, device_class, clicks FROM link_click_hourly ORDER BY 1, 2, 3")
        .associate { Triple(it["short_code"], it["epoch"], it["device_class"]) to it["clicks"] }

    @Test
    fun `stores each count under its link, hour and class`() {
        store.add(listOf(count("store-a", 3), count("store-a", 2, DeviceClass.MOBILE), count("store-b", 1, at = hour + 1)))

        assertThat(rows()).containsOnly(
            entry(Triple("store-a", hour * 3600, "desktop"), 3L),
            entry(Triple("store-a", hour * 3600, "mobile"), 2L),
            entry(Triple("store-b", (hour + 1) * 3600, "desktop"), 1L),
        )
    }

    @Test
    fun `adds to the count that is there, from a later batch or another instance`() {
        store.add(listOf(count("add-a", 3), count("add-b", 1)))
        store.add(listOf(count("add-a", 4)))

        assertThat(rows()).containsOnly(
            entry(Triple("add-a", hour * 3600, "desktop"), 7L),
            entry(Triple("add-b", hour * 3600, "desktop"), 1L),
        )
    }

    @Test
    fun `an empty batch writes nothing`() {
        store.add(emptyList())

        assertThat(rows()).isEmpty()
    }

    @Test
    fun `stores the hour exactly as the aggregator counted it`() {
        store.add(listOf(count("hour-a", 1)))

        assertThat(jdbc.queryForObject("SELECT hour FROM link_click_hourly WHERE short_code = 'hour-a'", Timestamp::class.java)?.toInstant())
            .isEqualTo(Instant.parse("2026-10-12T10:00:00Z"))
    }

    @Test
    fun `refuses a count that is not positive, as the check constraint says`() {
        val failure = assertFailsWith<DataIntegrityViolationException> { store.add(listOf(count("bad-a", 1), count("bad-b", 0))) }

        assertThat((failure.cause as SQLException).sqlState).isEqualTo("23514")
    }
}

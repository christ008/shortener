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
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.analytics.Language
import uy.ct.shortener.analytics.Referrer
import uy.ct.shortener.analytics.internal.ClickCount
import uy.ct.shortener.analytics.internal.CountKey
import uy.ct.shortener.analytics.internal.DeviceClass
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

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

    private val day = Instant.parse("2026-10-12T10:00:00Z").epochSecond / 86_400

    private fun count(code: String, clicks: Long, device: DeviceClass = DeviceClass.DESKTOP, at: Long = hour) = ClickCount(CountKey.Hourly(code, at, device), clicks)

    private fun referrer(code: String, referrer: Referrer, clicks: Long) = ClickCount(CountKey.ReferrerDaily(code, day, referrer), clicks)

    private fun language(code: String, language: Language, clicks: Long) = ClickCount(CountKey.LanguageDaily(code, day, language), clicks)

    private fun dailyRows(code: String) = jdbc.queryForList("select day::text as day, dimension, value, clicks from link_click_daily where short_code = ?", code)
        .associate { Triple(it["day"], it["dimension"], it["value"]) to it["clicks"] }

    private fun rows() = jdbc.queryForList("select short_code, extract(epoch from hour)::bigint as epoch, device_class, clicks from link_click_hourly order by 1, 2, 3")
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

        assertThat(jdbc.queryForObject("select hour from link_click_hourly where short_code = 'hour-a'", Timestamp::class.java)?.toInstant())
            .isEqualTo(Instant.parse("2026-10-12T10:00:00Z"))
    }

    @Test
    fun `stores a visitor's referrer and language under the link and its UTC day, and adds to them`() {
        store.add(
            listOf(
                referrer("daily-a", Referrer.Host("t.co"), 2),
                referrer("daily-a", Referrer.Direct, 1),
                referrer("daily-a", Referrer.Other, 4),
                language("daily-a", Language.Code("es"), 3),
                language("daily-a", Language.Unknown, 1),
            ),
        )
        store.add(listOf(referrer("daily-a", Referrer.Host("t.co"), 5)))

        assertThat(dailyRows("daily-a")).containsOnly(
            entry(Triple("2026-10-12", "referrer", "t.co"), 7L),
            entry(Triple("2026-10-12", "referrer", "(direct)"), 1L),
            entry(Triple("2026-10-12", "referrer", "(other)"), 4L),
            entry(Triple("2026-10-12", "language", "es"), 3L),
            entry(Triple("2026-10-12", "language", "(unknown)"), 1L),
        )
    }

    @Test
    fun `writes hourly and daily counts of one batch together`() {
        store.add(listOf(count("both-a", 2), language("both-a", Language.Code("pt"), 2)))

        assertThat(rows().keys.map { it.first }).contains("both-a")
        assertThat(dailyRows("both-a")).containsOnly(entry(Triple("2026-10-12", "language", "pt"), 2L))
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `a batch is one statement, so a daily count that is refused takes the hourly ones with it`() {
        assertFailsWith<DataIntegrityViolationException> {
            store.add(listOf(count("atomic-a", 3), language("atomic-a", Language.Code("es"), 0)))
        }

        assertThat(jdbc.queryForObject("select count(*) from link_click_hourly where short_code = 'atomic-a'", Long::class.java)).isZero()
        assertThat(dailyRows("atomic-a")).isEmpty()
    }

    @Test
    fun `refuses a count that is not positive, as the check constraint says`() {
        val failure = assertFailsWith<DataIntegrityViolationException> { store.add(listOf(count("bad-a", 1), count("bad-b", 0))) }

        assertThat(assertIs<SQLException>(failure.cause).sqlState).isEqualTo("23514")
    }
}

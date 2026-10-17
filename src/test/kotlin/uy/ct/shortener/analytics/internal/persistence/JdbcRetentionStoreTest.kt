package uy.ct.shortener.analytics.internal.persistence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.analytics.internal.RetentionStep
import javax.sql.DataSource

/**
 * `analytics_retention` (migration V9) against real Postgres, through [JdbcRetentionStore]: one step folds the oldest UTC day of
 * hours older than 90 days into days and deletes those hours, and deletes the oldest day older than 730 days; steps carry on until
 * nothing is left; what is recent is never touched; and an instance that does not get the lock reports busy. The rows are dated
 * from the database's own clock.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(TestcontainersConfiguration::class, JdbcRetentionStore::class)
class JdbcRetentionStoreTest {

    @Autowired
    lateinit var store: JdbcRetentionStore

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var dataSource: DataSource

    private fun hourly(code: String, daysAgo: Int, hour: Int, device: String, clicks: Long) {
        jdbc.update(
            "insert into link_click_hourly (short_code, hour, device_class, clicks) " +
                "values (?, ((now() at time zone 'UTC')::date - ?)::timestamp at time zone 'UTC' + make_interval(hours => ?), ?, ?)",
            code, daysAgo, hour, device, clicks,
        )
    }

    private fun daily(code: String, daysAgo: Int, value: String, clicks: Long) {
        jdbc.update(
            "insert into link_click_daily (short_code, day, dimension, value, clicks) values (?, (now() at time zone 'UTC')::date - ?, 'referrer', ?, ?)",
            code, daysAgo, value, clicks,
        )
    }

    private fun foldedDevice(daysAgo: Int, device: String, clicks: Long) {
        jdbc.update(
            "insert into link_click_daily_device (short_code, day, device_class, clicks) values ('folded', (now() at time zone 'UTC')::date - ?, ?, ?)",
            daysAgo, device, clicks,
        )
    }

    private fun hours(code: String) = jdbc.queryForObject("select count(*) from link_click_hourly where short_code = ?", Long::class.java, code)

    private fun folded(code: String) = jdbc.queryForList(
        "select ((now() at time zone 'UTC')::date - day) as days_ago, device_class, clicks from link_click_daily_device where short_code = ?",
        code,
    ).associate { (it["days_ago"] to it["device_class"]) to it["clicks"] }

    private fun stepUntilDone(): List<RetentionStep> = buildList {
        do {
            val step = store.step()
            add(step)
        } while (step is RetentionStep.Progress && size < 50)
    }

    @Test
    fun `folds the hours of each day older than 90 days into that day, one day a step, and deletes them`() {
        hourly("old", 100, 1, "desktop", 2)
        hourly("old", 100, 23, "desktop", 3)
        hourly("old", 100, 5, "bot", 7)
        hourly("old", 95, 12, "mobile", 4)

        val first = store.step()

        assertThat(first).isEqualTo(RetentionStep.Progress(hoursFolded = 3, rowsDeleted = 0))
        assertThat(folded("old")).containsOnly(entry(100 to "desktop", 5L), entry(100 to "bot", 7L))
        assertThat(hours("old")).isEqualTo(1L)

        assertThat(store.step()).isEqualTo(RetentionStep.Progress(hoursFolded = 1, rowsDeleted = 0))
        assertThat(folded("old")).containsEntry(95 to "mobile", 4L)
        assertThat(hours("old")).isZero()
        assertThat(store.step()).isEqualTo(RetentionStep.Done)
    }

    @Test
    fun `keeps the hours of the last 90 days, counted from the start of today in UTC`() {
        hourly("recent", 90, 0, "desktop", 1)
        hourly("recent", 1, 10, "desktop", 1)
        hourly("recent", 0, 0, "mobile", 1)

        assertThat(stepUntilDone()).containsExactly(RetentionStep.Done)
        assertThat(hours("recent")).isEqualTo(3L)
        assertThat(folded("recent")).isEmpty()
    }

    @Test
    fun `adds a day's hours to what was already folded for that day`() {
        jdbc.update(
            "insert into link_click_daily_device (short_code, day, device_class, clicks) values ('again', (now() at time zone 'UTC')::date - 100, 'desktop', 10)",
        )
        hourly("again", 100, 3, "desktop", 5)

        stepUntilDone()

        assertThat(folded("again")).containsOnly(entry(100 to "desktop", 15L))
    }

    @Test
    fun `deletes the days older than 730 days, from the folded counts and from the referrers and languages`() {
        foldedDevice(731, "desktop", 3)
        foldedDevice(730, "desktop", 4)
        daily("days", 800, "t.co", 1)
        daily("days", 731, "t.co", 1)
        daily("days", 730, "t.co", 1)

        val steps = stepUntilDone()

        assertThat(steps.last()).isEqualTo(RetentionStep.Done)
        val deleted = steps.sumOf { step ->
            when (step) {
                is RetentionStep.Progress -> step.rowsDeleted
                RetentionStep.Busy, RetentionStep.Done -> 0L
            }
        }
        assertThat(deleted).isEqualTo(3L)
        assertThat(folded("folded").keys).containsExactly(730 to "desktop")
        assertThat(jdbc.queryForObject("select count(*) from link_click_daily where short_code = 'days'", Long::class.java)).isEqualTo(1L)
    }

    @Test
    fun `reports busy, and does nothing, while another instance holds the lock`() {
        hourly("locked", 100, 1, "desktop", 2)

        dataSource.connection.use { other ->
            other.createStatement().execute("select pg_advisory_lock(hashtext('analytics_retention'))")
            try {
                assertThat(store.step()).isEqualTo(RetentionStep.Busy)
                assertThat(hours("locked")).isEqualTo(1L)
            } finally {
                other.createStatement().execute("select pg_advisory_unlock(hashtext('analytics_retention'))")
            }
        }

        assertThat(store.step()).isEqualTo(RetentionStep.Progress(hoursFolded = 1, rowsDeleted = 0))
    }
}

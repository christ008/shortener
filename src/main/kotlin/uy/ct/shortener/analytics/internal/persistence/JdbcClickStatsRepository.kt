package uy.ct.shortener.analytics.internal.persistence

import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import uy.ct.shortener.analytics.Granularity
import uy.ct.shortener.analytics.StatsWindow
import uy.ct.shortener.analytics.internal.BreakdownRow
import uy.ct.shortener.analytics.internal.ClickRow
import uy.ct.shortener.analytics.internal.ClickStatsRepository
import uy.ct.shortener.analytics.internal.DeviceClass
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.StorageUnavailableException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * [ClickStatsRepository] on plain JDBC.
 *
 * - One statement groups the hourly rows of the link inside the window into the buckets of its granularity. It reads a range of
 *   the primary key `(short_code, hour, device_class)`, so its cost follows the window, not the table.
 * - A window of days reads the view `link_click_day` (V9), which holds every day wherever retention keeps it: the hours of the last 90
 *   days, the days up to 730, and the months after. A day is in one table only, so nothing is counted twice. A window of hours reads the
 *   hours only: the service refuses one that reaches the folded days.
 * - The window is half open: an hour that starts at its end is not read.
 * - The granularity's unit is a value of the sealed type, never text from a request, and goes in as a parameter.
 * - The breakdown reads the view `link_click_breakdown` (V9): days, and months for what is older than 730 days. A period counts when it
 *   overlaps the window, and each row says the first and last day it covers. Values are read back as the sealed type they were written
 *   from ([StoredDimension]).
 * - Storage that cannot serve the read becomes [StorageUnavailableException], as the link repository does it.
 */
@Repository
class JdbcClickStatsRepository(private val jdbc: JdbcClient) : ClickStatsRepository {

    override fun read(shortCode: ShortCode, window: StatsWindow): List<ClickRow> = reportingUnavailability {
        val sql = when (window.granularity) {
            Granularity.Hour -> READ_HOURS
            Granularity.Day -> READ_DAYS
        }
        jdbc.sql(sql)
            .param("unit", window.granularity.unit)
            .param("shortCode", shortCode.value)
            .param("start", OffsetDateTime.ofInstant(window.start, ZoneOffset.UTC))
            .param("end", OffsetDateTime.ofInstant(window.end, ZoneOffset.UTC))
            .param("startDay", LocalDate.ofInstant(window.start, ZoneOffset.UTC))
            .param("endDay", LocalDate.ofInstant(window.end, ZoneOffset.UTC))
            .query { rows, _ ->
                ClickRow(
                    bucket = rows.getObject("bucket", OffsetDateTime::class.java).toInstant(),
                    device = DeviceClass.of(rows.getString("device_class")),
                    clicks = rows.getLong("clicks"),
                )
            }
            .list()
    }

    override fun breakdown(shortCode: ShortCode, days: StatsWindow): List<BreakdownRow> = reportingUnavailability {
        jdbc.sql(BREAKDOWN)
            .param("shortCode", shortCode.value)
            .param("start", LocalDate.ofInstant(days.start, ZoneOffset.UTC))
            .param("end", LocalDate.ofInstant(days.end, ZoneOffset.UTC))
            .query { rows, _ ->
                val value = rows.getString("value")
                val visitors = rows.getLong("clicks")
                val start = rows.getObject("first_day", LocalDate::class.java).atStartOfDay(ZoneOffset.UTC).toInstant()
                val end = rows.getObject("last_day", LocalDate::class.java).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
                when (val dimension = rows.getString("dimension")) {
                    StoredDimension.REFERRER -> BreakdownRow.FromReferrer(StoredDimension.referrer(value), visitors, start, end)
                    StoredDimension.LANGUAGE -> BreakdownRow.InLanguage(StoredDimension.language(value), visitors, start, end)
                    else -> error("unknown dimension '$dimension'")
                }
            }
            .list()
    }

    private fun <T> reportingUnavailability(statement: () -> T): T =
        try {
            statement()
        } catch (ex: DataAccessResourceFailureException) {
            throw StorageUnavailableException(ex)
        } catch (ex: QueryTimeoutException) {
            throw StorageUnavailableException(ex)
        } catch (ex: UncategorizedSQLException) {
            if (ex.sqlException?.sqlState != LOCK_NOT_AVAILABLE) throw ex
            throw StorageUnavailableException(ex)
        }

    private companion object {
        /** `lock_not_available`, what Postgres reports for a lock that `lock_timeout` gave up on. */
        const val LOCK_NOT_AVAILABLE = "55P03"

        const val READ_HOURS = """
            select date_trunc(:unit, hour, 'UTC') as bucket, device_class, sum(clicks)::bigint as clicks
            from link_click_hourly
            where short_code = :shortCode and hour >= :start and hour < :end
            group by 1, 2
        """

        const val READ_DAYS = """
            select (day::timestamp at time zone 'UTC') as bucket, device_class, sum(clicks)::bigint as clicks
            from link_click_day
            where short_code = :shortCode and day >= :startDay and day < :endDay
            group by 1, 2
        """

        const val BREAKDOWN = """
            select dimension, value, sum(clicks)::bigint as clicks, min(first_day) as first_day, max(last_day) as last_day
            from link_click_breakdown
            where short_code = :shortCode and last_day >= :start and first_day < :end
            group by 1, 2
        """
    }
}

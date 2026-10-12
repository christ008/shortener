package uy.ct.shortener.analytics.internal.persistence

import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import uy.ct.shortener.analytics.StatsWindow
import uy.ct.shortener.analytics.internal.ClickRow
import uy.ct.shortener.analytics.internal.ClickStatsRepository
import uy.ct.shortener.analytics.internal.DeviceClass
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.StorageUnavailableException
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * [ClickStatsRepository] on plain JDBC.
 *
 * - One statement groups the hourly rows of the link inside the window into the buckets of its granularity. It reads a range of
 *   the primary key `(short_code, hour, device_class)`, so its cost follows the window, not the table.
 * - The window is half open: an hour that starts at its end is not read.
 * - The granularity's unit is a value of the sealed type, never text from a request, and goes in as a parameter.
 * - Storage that cannot serve the read becomes [StorageUnavailableException], as the link repository does it.
 */
@Repository
class JdbcClickStatsRepository(private val jdbc: JdbcClient) : ClickStatsRepository {

    override fun read(shortCode: ShortCode, window: StatsWindow): List<ClickRow> = reportingUnavailability {
        jdbc.sql(READ)
            .param("unit", window.granularity.unit)
            .param("shortCode", shortCode.value)
            .param("start", OffsetDateTime.ofInstant(window.start, ZoneOffset.UTC))
            .param("end", OffsetDateTime.ofInstant(window.end, ZoneOffset.UTC))
            .query { rows, _ ->
                ClickRow(
                    bucket = rows.getObject("bucket", OffsetDateTime::class.java).toInstant(),
                    device = DeviceClass.of(rows.getString("device_class")),
                    clicks = rows.getLong("clicks"),
                )
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

        const val READ = """
            SELECT date_trunc(:unit, hour, 'UTC') AS bucket, device_class, sum(clicks)::bigint AS clicks
            FROM link_click_hourly
            WHERE short_code = :shortCode AND hour >= :start AND hour < :end
            GROUP BY 1, 2
        """
    }
}

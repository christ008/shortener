package uy.ct.shortener.analytics.internal.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.stereotype.Repository
import uy.ct.shortener.analytics.internal.ClickCount
import uy.ct.shortener.analytics.internal.ClickStore
import uy.ct.shortener.analytics.internal.CountKey
import java.sql.PreparedStatement

/**
 * [ClickStore] on plain JDBC.
 *
 * - One statement adds a whole batch, hourly and daily counts together: each kind goes in as arrays, and a writable `with` holds two
 *   `insert ... select from unnest(...) on conflict do update`, one per table. So a batch is atomic, costs one round trip, and two
 *   instances adding to one key add up.
 * - A batch holds each key once, as the aggregator guarantees, because one statement cannot change a row twice.
 * - An hour and a day travel as numbers since the epoch and become a timestamp and a date in the database.
 */
@Repository
class JdbcClickStore(private val jdbc: JdbcTemplate) : ClickStore {

    override fun add(counts: List<ClickCount>) {
        if (counts.isEmpty()) return
        val hourly = HourlyColumns()
        val daily = DailyColumns()
        counts.forEach { count ->
            when (val key = count.key) {
                is CountKey.Hourly -> hourly.add(key, count.clicks)
                is CountKey.ReferrerDaily -> daily.add(key.shortCode, key.epochDay, StoredDimension.REFERRER, StoredDimension.of(key.referrer), count.clicks)
                is CountKey.LanguageDaily -> daily.add(key.shortCode, key.epochDay, StoredDimension.LANGUAGE, StoredDimension.of(key.language), count.clicks)
            }
        }
        jdbc.update(
            ADD,
            PreparedStatementSetter { statement ->
                hourly.bind(statement)
                daily.bind(statement)
            },
        )
    }

    private class HourlyColumns {
        private val codes = mutableListOf<String>()
        private val seconds = mutableListOf<Long>()
        private val devices = mutableListOf<String>()
        private val clicks = mutableListOf<Long>()

        fun add(key: CountKey.Hourly, count: Long) {
            codes += key.shortCode
            seconds += key.epochSecond
            devices += key.device.value
            clicks += count
        }

        fun bind(statement: PreparedStatement) {
            val connection = statement.connection
            statement.setArray(1, connection.createArrayOf("text", codes.toTypedArray()))
            statement.setArray(2, connection.createArrayOf("int8", seconds.toTypedArray()))
            statement.setArray(3, connection.createArrayOf("text", devices.toTypedArray()))
            statement.setArray(4, connection.createArrayOf("int8", clicks.toTypedArray()))
        }
    }

    private class DailyColumns {
        private val codes = mutableListOf<String>()
        private val days = mutableListOf<Long>()
        private val dimensions = mutableListOf<String>()
        private val values = mutableListOf<String>()
        private val clicks = mutableListOf<Long>()

        fun add(code: String, epochDay: Long, dimension: String, value: String, count: Long) {
            codes += code
            days += epochDay
            dimensions += dimension
            values += value
            clicks += count
        }

        fun bind(statement: PreparedStatement) {
            val connection = statement.connection
            statement.setArray(5, connection.createArrayOf("text", codes.toTypedArray()))
            statement.setArray(6, connection.createArrayOf("int8", days.toTypedArray()))
            statement.setArray(7, connection.createArrayOf("text", dimensions.toTypedArray()))
            statement.setArray(8, connection.createArrayOf("text", values.toTypedArray()))
            statement.setArray(9, connection.createArrayOf("int8", clicks.toTypedArray()))
        }
    }

    private companion object {
        const val ADD = """
            with hourly as (
                insert into link_click_hourly (short_code, hour, device_class, clicks)
                select code, to_timestamp(epoch_second), device, clicks
                from unnest(?::text[], ?::int8[], ?::text[], ?::int8[]) as counts (code, epoch_second, device, clicks)
                on conflict (short_code, hour, device_class) do update set clicks = link_click_hourly.clicks + excluded.clicks
            )
            insert into link_click_daily (short_code, day, dimension, value, clicks)
            select code, date '1970-01-01' + epoch_day::int, dimension, value, clicks
            from unnest(?::text[], ?::int8[], ?::text[], ?::text[], ?::int8[]) as counts (code, epoch_day, dimension, value, clicks)
            on conflict (short_code, day, dimension, value) do update set clicks = link_click_daily.clicks + excluded.clicks
        """
    }
}

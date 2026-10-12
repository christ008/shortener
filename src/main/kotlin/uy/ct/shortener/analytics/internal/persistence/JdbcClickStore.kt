package uy.ct.shortener.analytics.internal.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.stereotype.Repository
import uy.ct.shortener.analytics.internal.ClickCount
import uy.ct.shortener.analytics.internal.ClickStore

/**
 * [ClickStore] on plain JDBC.
 *
 * - One statement adds a whole batch: the counts go in as arrays and `INSERT ... SELECT FROM unnest(...) ON CONFLICT DO UPDATE`
 *   adds each to its row, so a batch is atomic, costs one round trip, and two instances adding to one key add up.
 * - A batch holds each key once, as the aggregator guarantees, because one statement cannot change a row twice.
 * - The hour travels as seconds since the epoch and becomes a timestamp in the database.
 */
@Repository
class JdbcClickStore(private val jdbc: JdbcTemplate) : ClickStore {

    override fun add(counts: List<ClickCount>) {
        if (counts.isEmpty()) return
        jdbc.update(ADD, PreparedStatementSetter { statement ->
            val connection = statement.connection
            statement.setArray(1, connection.createArrayOf("text", counts.map { it.key.shortCode }.toTypedArray()))
            statement.setArray(2, connection.createArrayOf("int8", counts.map { it.key.epochSecond }.toTypedArray()))
            statement.setArray(3, connection.createArrayOf("text", counts.map { it.key.device.value }.toTypedArray()))
            statement.setArray(4, connection.createArrayOf("int8", counts.map { it.clicks }.toTypedArray()))
        })
    }

    private companion object {
        const val ADD = """
            INSERT INTO link_click_hourly (short_code, hour, device_class, clicks)
            SELECT code, to_timestamp(epoch_second), device, clicks
            FROM unnest(?::text[], ?::int8[], ?::text[], ?::int8[]) AS counts (code, epoch_second, device, clicks)
            ON CONFLICT (short_code, hour, device_class) DO UPDATE SET clicks = link_click_hourly.clicks + EXCLUDED.clicks
        """
    }
}

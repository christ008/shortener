package uy.ct.shortener.analytics.internal.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.stereotype.Repository
import uy.ct.shortener.analytics.internal.ClickCount
import uy.ct.shortener.analytics.internal.ClickStore

/**
 * [ClickStore] on plain JDBC.
 *
 * - One statement adds a whole batch: the counts go in as arrays and `insert ... select from unnest(...) on conflict do update`
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
            insert into link_click_hourly (short_code, hour, device_class, clicks)
            select code, to_timestamp(epoch_second), device, clicks
            from unnest(?::text[], ?::int8[], ?::text[], ?::int8[]) as counts (code, epoch_second, device, clicks)
            on conflict (short_code, hour, device_class) do update set clicks = link_click_hourly.clicks + excluded.clicks
        """
    }
}

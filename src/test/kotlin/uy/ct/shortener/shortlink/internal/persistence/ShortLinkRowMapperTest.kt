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
import uy.ct.shortener.shortlink.Actor
import uy.ct.shortener.shortlink.LinkStatus
import uy.ct.shortener.shortlink.ShortLink
import java.time.Instant
import kotlin.test.assertFailsWith

/**
 * Pins how nullability is read from a row: columns that may be NULL map to null, and a NULL in a column the schema
 * forbids one in is reported by name.
 *
 * The rows come from the real driver on real Postgres, as a `SELECT` of literals. The table cannot be used for the second
 * case, because its constraints would refuse to store such a row, which is the point of the check: it is there for the day
 * the schema and this mapping drift.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(TestcontainersConfiguration::class)
class ShortLinkRowMapperTest {

    @Autowired
    lateinit var jdbc: JdbcClient

    /** The mapping of one row whose columns are the SQL expressions given, over a row with every nullable column NULL. */
    private fun mapped(vararg columns: Pair<String, String>): ShortLink {
        val row = mapOf(
            "short_code" to "'abc1234'",
            "target_url" to "'https://example.com'",
            "created_by" to "NULL::text",
            "created_at" to "TIMESTAMPTZ '2026-01-01 00:00:00+00'",
            "disabled_at" to "NULL::timestamptz",
            "disabled_by" to "NULL::text",
        ) + columns
        val select = row.entries.joinToString(prefix = "SELECT ") { (name, expression) -> "$expression AS $name" }
        return jdbc.sql(select).query(ShortLinkRowMapper).single()
    }

    @Test
    fun `maps the columns that may be null to an unknown creator and an active status`() {
        val link = mapped()

        assertThat(link.createdBy).isEqualTo(Actor.Unknown)
        assertThat(link.status).isEqualTo(LinkStatus.Active)
        assertThat(link.isDisabled).isFalse
        assertThat(link.createdAt).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"))
    }

    @Test
    fun `names the column when a NOT NULL column comes back NULL`() {
        mapOf("short_code" to "NULL::text", "target_url" to "NULL::text", "created_at" to "NULL::timestamptz").forEach { (column, expression) ->
            val failure = assertFailsWith<IllegalStateException>("expected a NULL $column to be reported") {
                mapped(column to expression)
            }

            assertThat(failure.message).contains("short_link.$column", "NOT NULL")
        }
    }
}

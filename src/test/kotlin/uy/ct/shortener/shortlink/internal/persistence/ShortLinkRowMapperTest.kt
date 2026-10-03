package uy.ct.shortener.shortlink.internal.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.sql.ResultSet
import java.time.OffsetDateTime
import kotlin.test.assertFailsWith

/**
 * Pins how nullability is read from a row: columns that may be NULL map to null, and a NULL in a
 * column the schema forbids one in is reported by name. Needs no database, since a real one
 * would never return such a row.
 */
class ShortLinkRowMapperTest {

    private fun row(vararg columns: Pair<String, Any?>): ResultSet {
        val rs = Mockito.mock(ResultSet::class.java)
        val values = mapOf(
            "short_code" to "abc1234",
            "target_url" to "https://example.com",
            "created_by" to null,
            "created_at" to OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            "disabled_at" to null,
            "disabled_by" to null,
        ) + columns
        values.forEach { (name, value) ->
            Mockito.`when`(rs.getString(name)).thenReturn(value as? String)
            when (value) {
                null -> Mockito.`when`(rs.getObject(name, OffsetDateTime::class.java)).thenReturn(null)
                is OffsetDateTime -> Mockito.`when`(rs.getObject(name, OffsetDateTime::class.java)).thenReturn(value)
            }
        }
        return rs
    }

    @Test
    fun `maps the columns that may be null to null`() {
        val link = ShortLinkRowMapper.mapRow(row(), 0)

        assertThat(link.createdBy).isNull()
        assertThat(link.disabledAt).isNull()
        assertThat(link.disabledBy).isNull()
        assertThat(link.isDisabled).isFalse
    }

    @Test
    fun `names the column when a NOT NULL column comes back NULL`() {
        listOf("short_code", "target_url", "created_at").forEach { column ->
            val failure = assertFailsWith<IllegalStateException>("expected a NULL $column to be reported") {
                ShortLinkRowMapper.mapRow(row(column to null), 0)
            }

            assertThat(failure.message).contains("short_link.$column", "NOT NULL")
        }
    }
}

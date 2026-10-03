package uy.ct.shortener.shortlink.internal.persistence

import org.springframework.jdbc.core.RowMapper
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import java.net.URI
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Maps a `short_link` row to a [ShortLink]. JDBC hands back values of unknown nullability, so
 * each column is read as nullable or not according to the schema: `created_by`, `disabled_at` and
 * `disabled_by` may be NULL, the rest may not. A NULL where the schema forbids one means the
 * schema and this mapping have drifted, and is reported as such, naming the column, instead of
 * surfacing later as an anonymous null-pointer error.
 */
internal object ShortLinkRowMapper : RowMapper<ShortLink> {

    override fun mapRow(rs: ResultSet, rowNum: Int): ShortLink = ShortLink(
        shortCode = ShortCode(rs.required("short_code")),
        targetUrl = URI.create(rs.required("target_url")),
        createdBy = rs.getString("created_by"),
        createdAt = rs.requiredInstant("created_at"),
        disabledAt = rs.getObject("disabled_at", OffsetDateTime::class.java)?.toInstant(),
        disabledBy = rs.getString("disabled_by"),
    )

    private fun ResultSet.required(column: String): String =
        getString(column) ?: throw nullInRequiredColumn(column)

    private fun ResultSet.requiredInstant(column: String): Instant =
        getObject(column, OffsetDateTime::class.java)?.toInstant() ?: throw nullInRequiredColumn(column)

    private fun nullInRequiredColumn(column: String) =
        IllegalStateException("short_link.$column is NULL, but the schema declares it NOT NULL")
}

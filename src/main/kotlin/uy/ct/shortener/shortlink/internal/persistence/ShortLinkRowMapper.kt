package uy.ct.shortener.shortlink.internal.persistence

import org.springframework.jdbc.core.RowMapper
import uy.ct.shortener.shortlink.Actor
import uy.ct.shortener.shortlink.LinkStatus
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import java.net.URI
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Maps a `short_link` row to a [ShortLink].
 *
 * - `created_by`, `disabled_at` and `disabled_by` may be NULL and become [Actor.Unknown] or [LinkStatus.Active].
 * - A NULL in any other column is reported by column name.
 */
internal object ShortLinkRowMapper : RowMapper<ShortLink> {

    override fun mapRow(rs: ResultSet, rowNum: Int): ShortLink = ShortLink(
        shortCode = ShortCode(rs.required("short_code")),
        targetUrl = URI.create(rs.required("target_url")),
        createdBy = Actor.of(rs.getString("created_by")),
        createdAt = rs.requiredInstant("created_at"),
        status = rs.status(),
    )

    private fun ResultSet.status(): LinkStatus =
        getObject("disabled_at", OffsetDateTime::class.java)
            ?.let { LinkStatus.Disabled(it.toInstant(), Actor.of(getString("disabled_by"))) }
            ?: LinkStatus.Active

    private fun ResultSet.required(column: String): String =
        getString(column) ?: throw nullInRequiredColumn(column)

    private fun ResultSet.requiredInstant(column: String): Instant =
        getObject(column, OffsetDateTime::class.java)?.toInstant() ?: throw nullInRequiredColumn(column)

    private fun nullInRequiredColumn(column: String) =
        IllegalStateException("short_link.$column is NULL, but the schema declares it NOT NULL")
}

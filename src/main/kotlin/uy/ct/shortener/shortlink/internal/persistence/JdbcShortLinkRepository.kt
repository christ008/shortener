package uy.ct.shortener.shortlink.internal.persistence

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import uy.ct.shortener.shortlink.ShortLinkRepository
import java.net.URI
import java.sql.ResultSet
import java.time.OffsetDateTime
import kotlin.jvm.optionals.getOrNull

/**
 * [ShortLinkRepository] on plain JDBC through Spring's [JdbcClient], with the SQL written out
 * rather than generated. It leans on Postgres: `INSERT ... ON CONFLICT DO NOTHING RETURNING`
 * claims a code and reads the stored row back in one atomic statement, and the database assigns
 * `created_at`. The schema, including the constraints that mirror [ShortCode] and [ShortLink],
 * lives in the Flyway migrations.
 */
@Repository
class JdbcShortLinkRepository(private val jdbc: JdbcClient) : ShortLinkRepository {

    override fun findByShortCode(shortCode: ShortCode): ShortLink? =
        jdbc.sql(FIND)
            .param("shortCode", shortCode.value)
            .query(TO_SHORT_LINK)
            .optional()
            .getOrNull()

    override fun insertIfAbsent(shortCode: ShortCode, targetUrl: URI, createdBy: String): ShortLink? =
        jdbc.sql(INSERT_IF_ABSENT)
            .param("shortCode", shortCode.value)
            .param("targetUrl", targetUrl.toString())
            .param("createdBy", createdBy)
            .query(TO_SHORT_LINK)
            .optional()
            .getOrNull()

    private companion object {
        const val COLUMNS = "short_code, target_url, created_by, created_at"

        const val FIND = "SELECT $COLUMNS FROM short_link WHERE short_code = :shortCode"

        const val INSERT_IF_ABSENT = """
            INSERT INTO short_link (short_code, target_url, created_by)
            VALUES (:shortCode, :targetUrl, :createdBy)
            ON CONFLICT (short_code) DO NOTHING
            RETURNING $COLUMNS
        """

        val TO_SHORT_LINK = RowMapper { rs, _ ->
            ShortLink(
                shortCode = ShortCode(rs.getString("short_code")),
                targetUrl = URI.create(rs.getString("target_url")),
                createdBy = rs.getString("created_by"),
                createdAt = rs.instant("created_at"),
            )
        }

        fun ResultSet.instant(column: String) = getObject(column, OffsetDateTime::class.java).toInstant()
    }
}

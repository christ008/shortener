package uy.ct.shortener.shortlink.internal.persistence

import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import uy.ct.shortener.shortlink.LinkCursor
import uy.ct.shortener.shortlink.LinkOrder
import uy.ct.shortener.shortlink.LinkPage
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import uy.ct.shortener.shortlink.ShortLinkRepository
import uy.ct.shortener.shortlink.StorageUnavailableException
import java.net.URI
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.jvm.optionals.getOrNull

/**
 * [ShortLinkRepository] on plain JDBC through Spring's [JdbcClient], with the SQL written out
 * rather than generated. It leans on Postgres: `INSERT ... ON CONFLICT DO NOTHING RETURNING`
 * claims a code and reads the stored row back in one atomic statement, the database assigns
 * `created_at`, and listing pages by seeking past the last row of the previous one with a row comparison on the
 * indexed columns (keyset pagination), one extra row telling whether another page follows. Short codes sort in byte
 * order (`COLLATE "C"`), not the database's locale, so the order is the same everywhere. The schema, including the constraints that mirror [ShortCode] and [ShortLink],
 * lives in the Flyway migrations. Failing to get a connection, whether the pool is exhausted or
 * the database is down, is reported as [StorageUnavailableException] so Spring's data-access
 * types do not leak out of this adapter. So are the two limits the application role carries in the
 * database, a statement that runs past `statement_timeout` and a lock not obtained within
 * `lock_timeout` (Spring leaves that one uncategorised, so it is recognised by its SQLSTATE): both mean storage cannot
 * serve the request now, and trying again is the answer.
 */
@Repository
class JdbcShortLinkRepository(private val jdbc: JdbcClient) : ShortLinkRepository {

    override fun findByShortCode(shortCode: ShortCode): ShortLink? = reportingUnavailability {
        jdbc.sql(FIND)
            .param("shortCode", shortCode.value)
            .query(TO_SHORT_LINK)
            .optional()
            .getOrNull()
    }

    override fun insertIfAbsent(shortCode: ShortCode, targetUrl: URI, createdBy: String): ShortLink? = reportingUnavailability {
        jdbc.sql(INSERT_IF_ABSENT)
            .param("shortCode", shortCode.value)
            .param("targetUrl", targetUrl.toString())
            .param("createdBy", createdBy)
            .query(TO_SHORT_LINK)
            .optional()
            .getOrNull()
    }

    override fun list(createdBy: String?, order: LinkOrder, size: Int, after: LinkCursor?): LinkPage = reportingUnavailability {
        require(size in 1..ShortLinkRepository.MAX_PAGE_SIZE) { "a page has between 1 and ${ShortLinkRepository.MAX_PAGE_SIZE} links, not $size" }
        var statement = jdbc.sql(listSql(createdBy != null, order, after != null))
        if (createdBy != null) statement = statement.param("createdBy", createdBy)
        if (after != null) {
            statement = statement
                .param("createdAt", OffsetDateTime.ofInstant(after.createdAt, ZoneOffset.UTC))
                .param("shortCode", after.shortCode.value)
        }
        val fetched = statement.param("fetch", size + 1).query(TO_SHORT_LINK).list()
        val items = fetched.take(size)
        LinkPage(items, if (fetched.size > size) items.last().let { LinkCursor(it.createdAt, it.shortCode) } else null)
    }

    override fun disable(shortCode: ShortCode, disabledBy: String): ShortLink? = reportingUnavailability {
        jdbc.sql(DISABLE)
            .param("shortCode", shortCode.value)
            .param("disabledBy", disabledBy)
            .query(TO_SHORT_LINK)
            .optional()
            .getOrNull()
    }

    private inline fun <T> reportingUnavailability(statement: () -> T): T =
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

    internal companion object {
        /** `lock_not_available`, what Postgres reports for a lock that `lock_timeout` gave up on. */
        const val LOCK_NOT_AVAILABLE = "55P03"

        const val SHORT_CODE_IN_BYTE_ORDER = "short_code COLLATE \"C\""

        const val COLUMNS = "short_code, target_url, created_by, created_at, disabled_at, disabled_by"

        /**
         * The listing query. The row comparison names the position of the last link of the previous page; it has the
         * same direction on both columns as the index it is served from, which is what lets Postgres seek to it
         * instead of reading and discarding every link before it. The pieces are fixed text chosen by the caller's
         * arguments, never text from a request.
         */
        internal fun listSql(filteredByCreator: Boolean, order: LinkOrder, seeking: Boolean): String {
            val (direction, comparison) = if (order == LinkOrder.NEWEST_FIRST) "DESC" to "<" else "ASC" to ">"
            val conditions = listOfNotNull(
                "created_by = :createdBy".takeIf { filteredByCreator },
                "(created_at, $SHORT_CODE_IN_BYTE_ORDER) $comparison (:createdAt, CAST(:shortCode AS text) COLLATE \"C\")".takeIf { seeking },
            )
            val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
            return "SELECT $COLUMNS FROM short_link $where ORDER BY created_at $direction, $SHORT_CODE_IN_BYTE_ORDER $direction LIMIT :fetch"
        }

        const val FIND = "SELECT $COLUMNS FROM short_link WHERE short_code = :shortCode"

        const val INSERT_IF_ABSENT = """
            INSERT INTO short_link (short_code, target_url, created_by)
            VALUES (:shortCode, :targetUrl, :createdBy)
            ON CONFLICT (short_code) DO NOTHING
            RETURNING $COLUMNS
        """

        const val DISABLE = """
            UPDATE short_link
            SET disabled_at = COALESCE(disabled_at, now()), disabled_by = COALESCE(disabled_by, :disabledBy)
            WHERE short_code = :shortCode
            RETURNING $COLUMNS
        """

        val TO_SHORT_LINK = ShortLinkRowMapper
    }
}

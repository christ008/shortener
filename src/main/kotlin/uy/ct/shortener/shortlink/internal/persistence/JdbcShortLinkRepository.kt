package uy.ct.shortener.shortlink.internal.persistence

import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import uy.ct.shortener.shortlink.CreatedByFilter
import uy.ct.shortener.shortlink.InsertResult
import uy.ct.shortener.shortlink.InvalidSortException
import uy.ct.shortener.shortlink.LinkLookup
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import uy.ct.shortener.shortlink.ShortLinkRepository
import uy.ct.shortener.shortlink.StorageUnavailableException
import java.net.URI

/**
 * [ShortLinkRepository] on plain JDBC through Spring's [JdbcClient], with the SQL written out.
 *
 * - Relies on Postgres: `INSERT ... ON CONFLICT DO NOTHING RETURNING` claims a code and reads the
 *   row back in one atomic statement, and the database assigns `created_at`.
 * - Lists with `LIMIT`/`OFFSET` on indexed columns. One extra row tells whether another page follows.
 * - Sorts codes in byte order (`COLLATE "C"`), not the database's locale, so the order is the same
 *   everywhere.
 * - The schema, including the constraints that mirror [ShortCode] and [ShortLink], lives in the
 *   Flyway migrations.
 * - Storage that cannot serve the request now becomes [StorageUnavailableException], so Spring's
 *   data-access types do not leak out of this adapter, and trying again is the answer. That covers
 *   a failure to get a connection (pool exhausted or database down), a statement past
 *   `statement_timeout` and a lock not obtained within `lock_timeout`. Spring leaves the last one
 *   uncategorised, so it is recognised by its SQLSTATE.
 */
@Repository
class JdbcShortLinkRepository(private val jdbc: JdbcClient) : ShortLinkRepository {

    override fun findByShortCode(shortCode: ShortCode): LinkLookup = reportingUnavailability {
        jdbc.sql(FIND)
            .param("shortCode", shortCode.value)
            .query(TO_SHORT_LINK)
            .optional()
            .map<LinkLookup> { LinkLookup.Found(it) }
            .orElse(LinkLookup.Missing)
    }

    override fun insertIfAbsent(shortCode: ShortCode, targetUrl: URI, createdBy: String): InsertResult = reportingUnavailability {
        jdbc.sql(INSERT_IF_ABSENT)
            .param("shortCode", shortCode.value)
            .param("targetUrl", targetUrl.toString())
            .param("createdBy", createdBy)
            .query(TO_SHORT_LINK)
            .optional()
            .map<InsertResult> { InsertResult.Created(it) }
            .orElse(InsertResult.Taken)
    }

    override fun list(filter: CreatedByFilter, pageable: Pageable): Page<ShortLink> = reportingUnavailability {
        require(pageable.isPaged) { "an unpaged request would load every link" }
        val statement = when (filter) {
            CreatedByFilter.Anyone -> jdbc.sql(listSql(where = "", pageable.sort))
            is CreatedByFilter.Only -> jdbc.sql(listSql(where = "WHERE created_by = :createdBy", pageable.sort)).param("createdBy", filter.client)
        }
        val fetched = statement
            .param("fetch", pageable.pageSize + 1)
            .param("offset", pageable.offset)
            .query(TO_SHORT_LINK)
            .list()
        val content = fetched.take(pageable.pageSize)
        PageImpl(content, pageable, totalOf(filter, pageable, fetched.size))
    }

    override fun disable(shortCode: ShortCode, disabledBy: String): LinkLookup = reportingUnavailability {
        jdbc.sql(DISABLE)
            .param("shortCode", shortCode.value)
            .param("disabledBy", disabledBy)
            .query(TO_SHORT_LINK)
            .optional()
            .map<LinkLookup> { LinkLookup.Found(it) }
            .orElse(LinkLookup.Missing)
    }

    private fun listSql(where: String, sort: Sort) =
        "SELECT $COLUMNS FROM short_link $where ORDER BY ${orderBy(sort)} LIMIT :fetch OFFSET :offset"

    /**
     * The total number of matches. The page already knows it when it ends the listing, because it then holds
     * every match after the offset, or when it is the first page and empty. Otherwise it is counted.
     */
    private fun totalOf(filter: CreatedByFilter, pageable: Pageable, fetched: Int): Long = when {
        fetched in 1..pageable.pageSize -> pageable.offset + fetched
        fetched == 0 && pageable.offset == 0L -> 0
        else -> count(filter)
    }

    private fun count(filter: CreatedByFilter): Long =
        when (filter) {
            CreatedByFilter.Anyone -> jdbc.sql(COUNT_ALL)
            is CreatedByFilter.Only -> jdbc.sql(COUNT_BY_CREATOR).param("createdBy", filter.client)
        }.query(Long::class.java).single()

    private fun orderBy(sort: Sort): String {
        val requested = sort.map { order ->
            val column = SORT_COLUMNS[order.property]
                ?: throw InvalidSortException(order.property, ShortLinkRepository.SORTABLE_PROPERTIES)
            "$column ${if (order.isAscending) "ASC" else "DESC"}"
        }.toList()
        val direction = sort.firstOrNull()?.direction ?: Sort.Direction.DESC
        return (requested.ifEmpty { listOf("created_at DESC") } + "$SHORT_CODE_IN_BYTE_ORDER ${direction.name}").joinToString()
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

    private companion object {
        /** `lock_not_available`, what Postgres reports for a lock that `lock_timeout` gave up on. */
        const val LOCK_NOT_AVAILABLE = "55P03"

        const val SHORT_CODE_IN_BYTE_ORDER = "short_code COLLATE \"C\""

        val SORT_COLUMNS = mapOf("createdAt" to "created_at", "shortCode" to SHORT_CODE_IN_BYTE_ORDER)

        const val COLUMNS = "short_code, target_url, created_by, created_at, disabled_at, disabled_by"

        const val COUNT_ALL = "SELECT count(*) FROM short_link"

        const val COUNT_BY_CREATOR = "SELECT count(*) FROM short_link WHERE created_by = :createdBy"

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

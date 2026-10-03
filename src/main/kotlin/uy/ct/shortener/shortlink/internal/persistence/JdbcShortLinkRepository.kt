package uy.ct.shortener.shortlink.internal.persistence

import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.domain.SliceImpl
import org.springframework.data.domain.Sort
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import uy.ct.shortener.shortlink.InvalidSortException
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import uy.ct.shortener.shortlink.ShortLinkRepository
import uy.ct.shortener.shortlink.StorageUnavailableException
import java.net.URI
import kotlin.jvm.optionals.getOrNull

/**
 * [ShortLinkRepository] on plain JDBC through Spring's [JdbcClient], with the SQL written out
 * rather than generated. It leans on Postgres: `INSERT ... ON CONFLICT DO NOTHING RETURNING`
 * claims a code and reads the stored row back in one atomic statement, the database assigns
 * `created_at`, and listing pages with `LIMIT`/`OFFSET` on indexed columns, one extra row telling whether another page follows. Short codes sort in byte order (`COLLATE "C"`), not the database's locale, so the order is the same everywhere. The schema, including the constraints that mirror [ShortCode] and [ShortLink],
 * lives in the Flyway migrations. Failing to get a connection, whether the pool is exhausted or
 * the database is down, is reported as [StorageUnavailableException] so Spring's data-access
 * types do not leak out of this adapter.
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

    override fun list(createdBy: String?, pageable: Pageable): Slice<ShortLink> = reportingUnavailability {
        require(pageable.isPaged) { "an unpaged request would load every link" }
        val where = if (createdBy != null) "WHERE created_by = :createdBy" else ""
        var statement = jdbc.sql("SELECT $COLUMNS FROM short_link $where ORDER BY ${orderBy(pageable.sort)} LIMIT :fetch OFFSET :offset")
        if (createdBy != null) statement = statement.param("createdBy", createdBy)
        val fetched = statement
            .param("fetch", pageable.pageSize + 1)
            .param("offset", pageable.offset)
            .query(TO_SHORT_LINK)
            .list()
        SliceImpl(fetched.take(pageable.pageSize), pageable, fetched.size > pageable.pageSize)
    }

    override fun disable(shortCode: ShortCode, disabledBy: String): ShortLink? = reportingUnavailability {
        jdbc.sql(DISABLE)
            .param("shortCode", shortCode.value)
            .param("disabledBy", disabledBy)
            .query(TO_SHORT_LINK)
            .optional()
            .getOrNull()
    }

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
        }

    private companion object {
        const val SHORT_CODE_IN_BYTE_ORDER = "short_code COLLATE \"C\""

        val SORT_COLUMNS = mapOf("createdAt" to "created_at", "shortCode" to SHORT_CODE_IN_BYTE_ORDER)

        const val COLUMNS = "short_code, target_url, created_by, created_at, disabled_at, disabled_by"

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

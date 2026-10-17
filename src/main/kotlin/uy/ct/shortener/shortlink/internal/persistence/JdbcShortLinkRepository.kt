package uy.ct.shortener.shortlink.internal.persistence

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.jdbc.UncategorizedSQLException
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
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * [ShortLinkRepository] on plain JDBC through Spring's [JdbcClient].
 *
 * - `insert ... on conflict do nothing returning` claims a code and reads the row back in one atomic statement. The database
 *   assigns `created_at`.
 * - Lists with `limit`/`offset` on indexed columns. One extra row tells whether another page follows.
 * - Sorts codes in byte order (`collate "C"`).
 * - The schema, including the constraints that mirror [ShortCode] and [ShortLink], is in the Flyway migrations.
 * - Storage that cannot serve the request becomes [StorageUnavailableException]: a failure to get a connection, a statement
 *   past `statement_timeout`, or a lock not obtained within `lock_timeout` (recognised by SQLSTATE, which Spring leaves
 *   uncategorised).
 * - The first such failure in any ten seconds is logged as a warning with its type, never the statement or its parameters.
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
            is CreatedByFilter.Only -> jdbc.sql(listSql(where = "where created_by = :createdBy", pageable.sort)).param("createdBy", filter.client)
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
        "select $COLUMNS from short_link $where order by ${orderBy(sort)} limit :fetch offset :offset"

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
            "$column ${if (order.isAscending) "asc" else "desc"}"
        }.toList()
        val direction = sort.firstOrNull()?.direction ?: Sort.Direction.DESC
        return (requested.ifEmpty { listOf("created_at desc") } + "$SHORT_CODE_IN_BYTE_ORDER ${direction.name.lowercase()}").joinToString()
    }

    private val lastUnavailableLogged = AtomicLong(System.nanoTime() - UNAVAILABLE_LOG_INTERVAL_NANOS)

    private inline fun <T> reportingUnavailability(statement: () -> T): T =
        try {
            statement()
        } catch (ex: DataAccessResourceFailureException) {
            throw unavailable(ex)
        } catch (ex: QueryTimeoutException) {
            throw unavailable(ex)
        } catch (ex: UncategorizedSQLException) {
            if (ex.sqlException?.sqlState != LOCK_NOT_AVAILABLE) throw ex
            throw unavailable(ex)
        }

    private fun unavailable(cause: RuntimeException): StorageUnavailableException {
        val now = System.nanoTime()
        val last = lastUnavailableLogged.get()
        if (now - last >= UNAVAILABLE_LOG_INTERVAL_NANOS && lastUnavailableLogged.compareAndSet(last, now)) {
            LOGGER.warn("Storage unavailable ({}), answering 503; further failures are logged at most every 10 seconds", cause.javaClass.simpleName)
        }
        return StorageUnavailableException(cause)
    }

    private companion object {
        val LOGGER: Logger = LoggerFactory.getLogger(JdbcShortLinkRepository::class.java)

        val UNAVAILABLE_LOG_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos()

        /** `lock_not_available`, what Postgres reports for a lock that `lock_timeout` gave up on. */
        const val LOCK_NOT_AVAILABLE = "55P03"

        const val SHORT_CODE_IN_BYTE_ORDER = "short_code collate \"C\""

        val SORT_COLUMNS = mapOf("createdAt" to "created_at", "shortCode" to SHORT_CODE_IN_BYTE_ORDER)

        const val COLUMNS = "short_code, target_url, created_by, created_at, disabled_at, disabled_by"

        const val COUNT_ALL = "select count(*) from short_link"

        const val COUNT_BY_CREATOR = "select count(*) from short_link where created_by = :createdBy"

        const val FIND = "select $COLUMNS from short_link where short_code = :shortCode"

        const val INSERT_IF_ABSENT = """
            insert into short_link (short_code, target_url, created_by)
            values (:shortCode, :targetUrl, :createdBy)
            on conflict (short_code) do nothing
            returning $COLUMNS
        """

        const val DISABLE = """
            update short_link
            set disabled_at = coalesce(disabled_at, now()), disabled_by = coalesce(disabled_by, :disabledBy)
            where short_code = :shortCode
            returning $COLUMNS
        """

        val TO_SHORT_LINK = ShortLinkRowMapper
    }
}

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
import uy.ct.shortener.shortlink.LinkOrder
import java.time.OffsetDateTime

/**
 * The point of listing by position is that a page costs the same wherever it is, and that only holds if Postgres can
 * seek to the position in an index and read the page from there already in order. Measured on two million links, a page a
 * million links in took 0.06 ms that way and 255 ms by offset. A change to the query that stops the index serving it, such as
 * a comparison on a column with another collation than the index's, would still return the right rows, just slowly,
 * and no result-based test would notice. This asks Postgres for the plan of every form of the query with sequential
 * scans and sorts switched off, which leaves it no choice but the best one the query allows, on a table with no rows to
 * tempt it into a different one, and fails if that plan still sorts, scans the table, or applies the position as a filter
 * rather than a condition of the index.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(TestcontainersConfiguration::class)
class ListingPlanTest {

    @Autowired
    lateinit var jdbc: JdbcClient

    private fun plan(filtered: Boolean, order: LinkOrder, seeking: Boolean): String {
        jdbc.sql("SET LOCAL enable_seqscan = off").update()
        jdbc.sql("SET LOCAL enable_sort = off").update()
        var statement = jdbc.sql("EXPLAIN " + JdbcShortLinkRepository.listSql(filtered, order, seeking))
        if (filtered) statement = statement.param("createdBy", "client")
        if (seeking) statement = statement.param("createdAt", OffsetDateTime.now()).param("shortCode", "abcdefg")
        return statement.param("fetch", 51).query(String::class.java).list().joinToString("\n")
    }

    @Test
    fun `every form of the listing query reads its page from an index, in order, starting at the position`() {
        for (filtered in listOf(false, true)) for (order in LinkOrder.entries) for (seeking in listOf(false, true)) {
            val plan = plan(filtered, order, seeking)
            val form = "filtered=$filtered, $order, seeking=$seeking:\n$plan"

            assertThat(plan).describedAs(form).contains(if (filtered) "short_link_created_by_idx" else "short_link_created_at_idx")
            assertThat(plan).describedAs(form).doesNotContain("Sort").doesNotContain("Seq Scan")
            if (seeking) assertThat(plan).describedAs(form).containsPattern("Index Cond: .*ROW\\(created_at, \\(short_code\\)::text\\)")
            if (order == LinkOrder.OLDEST_FIRST && !filtered) assertThat(plan).describedAs(form).contains("Index Scan Backward")
        }
    }
}

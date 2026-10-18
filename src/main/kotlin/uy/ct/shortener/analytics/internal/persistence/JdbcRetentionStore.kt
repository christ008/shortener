package uy.ct.shortener.analytics.internal.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import uy.ct.shortener.analytics.internal.RetentionStep
import uy.ct.shortener.analytics.internal.RetentionStore

/**
 * [RetentionStore] on plain JDBC: one call of `analytics_retention()` (migration V9) per step.
 *
 * - The function runs as its owner, `shortener_retention`, so the application role, which has no `DELETE`, can move counts only as the
 *   function's fixed rules say, and only through it.
 * - Each call is a statement of its own, within the role's `statement_timeout`.
 */
@Repository
class JdbcRetentionStore(private val jdbc: JdbcClient) : RetentionStore {

    override fun step(): RetentionStep =
        jdbc.sql(STEP)
            .query { rows, _ ->
                val hoursFolded = rows.getLong("hours_folded")
                val daysFolded = rows.getLong("days_folded")
                when {
                    rows.getBoolean("busy") -> RetentionStep.Busy
                    hoursFolded == 0L && daysFolded == 0L -> RetentionStep.Done
                    else -> RetentionStep.Progress(hoursFolded, daysFolded)
                }
            }
            .single()

    private companion object {
        const val STEP = "select busy, hours_folded, days_folded from analytics_retention()"
    }
}

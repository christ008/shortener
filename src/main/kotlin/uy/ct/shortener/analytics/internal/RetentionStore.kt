package uy.ct.shortener.analytics.internal

/**
 * What one step of retention did.
 *
 * - [Busy]: another instance holds the lock and is doing the work.
 * - [Done]: nothing was old enough to fold or delete.
 * - [Progress]: it folded [Progress.hoursFolded] hourly rows into days, and [Progress.daysFolded] days of the daily tables into their
 *   months; there may be more.
 */
sealed interface RetentionStep {

    data object Busy : RetentionStep

    data object Done : RetentionStep

    data class Progress(val hoursFolded: Long, val daysFolded: Long) : RetentionStep
}

/**
 * Applies the retention of the click counts, one step at a time: the rules (hours for 90 days, days for 730, then months for ever) live
 * in the database, in `analytics_retention`, and a step moves at most one day of each kind.
 */
fun interface RetentionStore {

    fun step(): RetentionStep
}

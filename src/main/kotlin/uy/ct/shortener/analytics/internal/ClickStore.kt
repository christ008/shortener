package uy.ct.shortener.analytics.internal

/**
 * Where counts of clicks are kept.
 */
fun interface ClickStore {

    /**
     * Adds each count to the one stored under its key, creating it if there is none, all or nothing.
     *
     * @throws RuntimeException if the counts could not be written, in which case none was
     */
    fun add(counts: List<ClickCount>)
}

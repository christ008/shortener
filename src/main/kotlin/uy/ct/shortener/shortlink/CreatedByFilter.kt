package uy.ct.shortener.shortlink

/**
 * Which links a listing covers, by who created them.
 *
 * - [Anyone]: every link. Only administrators may ask for this.
 * - [Only]: the links of one client.
 *
 * [isLimitedTo] is what the method-security rules call, so the filter is checked against the caller
 * without unpacking it.
 */
sealed interface CreatedByFilter {

    /** True when the listing is restricted to exactly [client]. */
    fun isLimitedTo(client: String): Boolean

    data object Anyone : CreatedByFilter {
        override fun isLimitedTo(client: String) = false
    }

    data class Only(val client: String) : CreatedByFilter {
        override fun isLimitedTo(client: String) = this.client == client
    }
}

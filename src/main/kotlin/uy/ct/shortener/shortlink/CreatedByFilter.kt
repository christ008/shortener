package uy.ct.shortener.shortlink

/**
 * Which links a listing covers, by who created them.
 *
 * - [Anyone]: every link. Only administrators may ask for this.
 * - [Only]: the links of one client.
 *
 * - [isLimitedTo] is what the method-security rules call, so the filter is checked against the caller
 *   without unpacking it.
 * - [of] is the default a listing takes when the caller does not name a client.
 */
sealed interface CreatedByFilter {

    /** True when the listing is restricted to exactly [client]. */
    fun isLimitedTo(client: String): Boolean

    companion object {
        /**
         * The filter for a caller who may have asked for one client's links ([requestedClient]).
         *
         * - A client that was named gets those links. Whether the caller may see them is decided by the service.
         * - Otherwise an administrator gets every link, and anyone else gets their own.
         */
        fun of(requestedClient: String?, caller: String, callerIsAdministrator: Boolean): CreatedByFilter = when {
            requestedClient != null -> Only(requestedClient)
            callerIsAdministrator -> Anyone
            else -> Only(caller)
        }
    }

    data object Anyone : CreatedByFilter {
        override fun isLimitedTo(client: String) = false
    }

    data class Only(val client: String) : CreatedByFilter {
        override fun isLimitedTo(client: String) = this.client == client
    }
}

package uy.ct.shortener.shortlink

/**
 * Which links a listing covers, by who created them.
 *
 * - [Anyone]: every link. Only administrators may ask for this.
 * - [Only]: the links of one client.
 * - [isLimitedTo]: called by the method-security rules.
 * - [of]: the filter for a caller who may have named a client.
 */
sealed interface CreatedByFilter {

    /** True when the listing is restricted to exactly [client]. */
    fun isLimitedTo(client: String): Boolean

    companion object {
        /**
         * The filter for a caller who may have asked for one client's links ([requestedClient]).
         *
         * - A named client gets those links. The service decides whether the caller may see them.
         * - Otherwise an administrator gets every link and anyone else gets their own.
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

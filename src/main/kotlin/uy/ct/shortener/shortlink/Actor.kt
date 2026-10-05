package uy.ct.shortener.shortlink

/**
 * Who acted on a link, as a value instead of a nullable name.
 *
 * - [Client]: an authenticated client, by the name its access token carries.
 * - [Unknown]: links stored before the creator was recorded.
 */
sealed interface Actor {

    data object Unknown : Actor

    data class Client(val name: String) : Actor

    companion object {
        /** Reads a stored name, where an absent value means [Unknown]. */
        fun of(name: String?): Actor = if (name == null) Unknown else Client(name)
    }
}

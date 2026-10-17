package uy.ct.shortener.analytics

/**
 * Where a visitor came from, as far as its `Referer` header says: a [Host], no referrer at all ([Direct]), or [Other] once a link
 * has as many hosts in a day as an instance admits.
 *
 * - Only the host is kept, lowercased, without a trailing dot or a leading `www.`. The path, the query, a port and any user info are
 *   dropped, so `https://www.Example.com:8443/a?b` is `example.com`.
 * - A header with no host, with characters a host name cannot hold, or longer than a host name can be, is [Direct]: it says nothing
 *   that can be counted.
 * - [of] reads the header by hand rather than with `URI`, because it runs in the thread of every redirect: it costs a
 *   fraction of the parse, and a malformed header costs no exception.
 */
sealed interface Referrer {

    data object Direct : Referrer

    data object Other : Referrer

    data class Host(val name: String) : Referrer {
        init {
            require(isHostName(name)) { "not a host name: '$name'" }
        }
    }

    companion object {

        private const val MAX_HEADER = 2048

        private const val MAX_HOST = 253

        fun of(header: String): Referrer {
            val text = header.take(MAX_HEADER).trim()
            val schemeEnd = text.indexOf("://")
            if (schemeEnd <= 0 || !text.substring(0, schemeEnd).all { it.isLetterOrDigit() || it in "+.-" }) return Direct
            val rest = text.substring(schemeEnd + 3)
            val authority = rest.substring(0, rest.indexOfFirst { it in "/?#" }.let { if (it < 0) rest.length else it })
            val name = normalized(hostOf(authority.substringAfterLast('@')))
            return if (isHostName(name)) Host(name) else Direct
        }

        private fun hostOf(hostAndPort: String): String = when {
            hostAndPort.startsWith('[') -> hostAndPort.substring(0, hostAndPort.indexOf(']') + 1)
            else -> hostAndPort.substringBefore(':')
        }

        private fun normalized(host: String): String = host.lowercase().removeSuffix(".").removePrefix("www.")

        private fun isHostName(name: String): Boolean = when {
            name.isEmpty() || name.length > MAX_HOST -> false
            name.startsWith('[') -> name.endsWith(']') && name.length > 2 && name.substring(1, name.length - 1).all { it.isHexDigit() || it == ':' || it == '.' }
            else -> name.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' } && !name.startsWith('.') && !name.startsWith('-')
        }

        private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f'
    }
}

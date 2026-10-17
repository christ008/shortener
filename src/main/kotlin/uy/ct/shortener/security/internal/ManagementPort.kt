package uy.ct.shortener.security.internal

import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment

/**
 * Tells the requests that came in through the management port from the application's.
 *
 * - The management server shares the application's security chain and has no TLS, so its requests carry no client certificate.
 * - Its port is known only once that server is up (`local.management.port`), so the environment is asked until it answers, and the answer is kept.
 * - With no separate management port there is none to tell apart, and [serves] is `false` for every request.
 */
class ManagementPort(private val environment: Environment) {

    @Volatile
    private var known = UNKNOWN

    fun serves(request: HttpServletRequest): Boolean {
        if (known == UNKNOWN) known = environment.getProperty(PROPERTY, Int::class.java) ?: UNKNOWN
        return request.localPort == known
    }

    private companion object {
        const val PROPERTY = "local.management.port"
        const val UNKNOWN = -1
    }
}

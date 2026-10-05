package uy.ct.shortener.security.internal

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders

/**
 * The authorization scheme a request used, with [NONE] for a missing or unrecognised one.
 */
enum class AuthScheme(val challengeName: String) {
    DPOP("DPoP"),
    BEARER("Bearer"),
    NONE(""),
    ;

    companion object {
        fun of(request: HttpServletRequest): AuthScheme {
            val used = request.getHeader(HttpHeaders.AUTHORIZATION).orEmpty().substringBefore(' ')
            return entries.firstOrNull { it != NONE && it.challengeName.equals(used, ignoreCase = true) } ?: NONE
        }
    }
}

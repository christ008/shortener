package uy.ct.shortener.security.internal

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.jwt.Jwt

/**
 * What a token that grants the administrator scope must show: that the person it was issued to signed in with a second factor.
 *
 * - A token with the [scope] is accepted when its `acr` claim is a number of at least [minLevel], the level a sign-in reaches with a
 *   one-time code (Keycloak writes the level as a number unless the realm names it), or is one of [acrValues], the names a realm
 *   gives such levels, or when its client is one of [exemptClients].
 * - [exemptClients] are service accounts, which authenticate with a key and cannot have a second factor. An empty list exempts none,
 *   so a token of an administrator that nobody listed, or with no `acr`, or with a lower or an unreadable one, is refused.
 * - A token without the scope is not looked at.
 * - A refusal is `insufficient_user_authentication` with the value that would do (RFC 9470, section 3), the first of [acrValues] or
 *   [minLevel], so that a client can sign the person in again with it.
 */
class AdminAssurance(
    private val scope: String,
    private val minLevel: Int,
    private val acrValues: List<String>,
    private val exemptClients: Set<String>,
) {

    fun require(token: Jwt, authorities: Collection<GrantedAuthority>) {
        if (authorities.none { it.authority == scope }) return
        if (token.getClaimAsString(AUTHORIZED_PARTY) in exemptClients) return
        val acr = token.getClaimAsString(AUTHENTICATION_CLASS)
        if (acr in acrValues || (acr?.toIntOrNull() ?: Int.MIN_VALUE) >= minLevel) return
        throw InsufficientUserAuthenticationException(listOf(acrValues.firstOrNull() ?: minLevel.toString()))
    }

    private companion object {
        const val AUTHORIZED_PARTY = "azp"
        const val AUTHENTICATION_CLASS = "acr"
    }
}

/** A token that lacks the level of authentication the request needs (RFC 9470), with the `acr` values that would do. */
class InsufficientUserAuthenticationException(val acrValues: List<String>) : OAuth2AuthenticationException(
    OAuth2Error(CODE, "The administrator scope needs a sign-in with a second factor", null),
) {
    companion object {
        const val CODE = "insufficient_user_authentication"
    }
}

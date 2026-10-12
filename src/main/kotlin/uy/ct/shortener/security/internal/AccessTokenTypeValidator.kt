package uy.ct.shortener.security.internal

import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt

/**
 * Accepts an access token and refuses any other kind of token the identity provider signs (RFC 9068, section 4).
 *
 * - A token whose JOSE `typ` header is [headerType], with or without the `application/` prefix, is an access token.
 * - A token whose header says `JWT` is one only when its own `typ` claim is one of [claimTypes]. Keycloak marks its access
 *   tokens `Bearer`, or `DPoP` when bound, and its ID and refresh tokens `ID` and `Refresh`. A client registered by itself gets
 *   no `at+jwt` header, since that is a setting of each client, so the claim is what tells its tokens apart.
 * - An empty [claimTypes] accepts the header alone.
 */
class AccessTokenTypeValidator(private val headerType: String, private val claimTypes: Set<String>) : OAuth2TokenValidator<Jwt> {

    override fun validate(token: Jwt): OAuth2TokenValidatorResult =
        if (isAccessToken(token)) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The token is not an access token", null))
        }

    private fun isAccessToken(token: Jwt): Boolean {
        val header = (token.headers["typ"] as? String)?.removePrefix("application/")
        return when {
            header.equals(headerType, ignoreCase = true) -> true
            header.equals("JWT", ignoreCase = true) -> token.getClaimAsString("typ") in claimTypes
            else -> false
        }
    }
}

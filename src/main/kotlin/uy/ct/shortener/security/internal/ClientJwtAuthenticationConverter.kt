package uy.ct.shortener.security.internal

import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter

/**
 * Turns a validated access token into an authentication named after its client.
 *
 * - The name is the [clientIdClaim] of the token.
 * - Each granted scope becomes an authority named exactly as the scope.
 * - A token without the claim is invalid.
 * - A token with the administrator scope must pass [adminAssurance].
 */
class ClientJwtAuthenticationConverter(
    private val clientIdClaim: String,
    private val adminAssurance: AdminAssurance,
) : Converter<Jwt, AbstractAuthenticationToken> {

    private val scopes = JwtGrantedAuthoritiesConverter().apply { setAuthorityPrefix("") }

    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val client = jwt.getClaimAsString(clientIdClaim)
            ?: throw InvalidBearerTokenException("The token has no '$clientIdClaim' claim")
        val authorities = scopes.convert(jwt).orEmpty()
        adminAssurance.require(jwt, authorities)
        return JwtAuthenticationToken(jwt, authorities, client)
    }
}

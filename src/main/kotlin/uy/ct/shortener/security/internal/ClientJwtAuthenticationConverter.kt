package uy.ct.shortener.security.internal

import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter

/**
 * Turns a validated access token into an authentication named after its client, taken from
 * [clientIdClaim], with one authority per granted scope, named exactly as the scope. A token
 * without that claim is rejected as invalid, since it cannot be attributed to a client.
 */
class ClientJwtAuthenticationConverter(private val clientIdClaim: String) : Converter<Jwt, AbstractAuthenticationToken> {

    private val scopes = JwtGrantedAuthoritiesConverter().apply { setAuthorityPrefix("") }

    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val client = jwt.getClaimAsString(clientIdClaim)
            ?: throw InvalidBearerTokenException("The token has no '$clientIdClaim' claim")
        return JwtAuthenticationToken(jwt, scopes.convert(jwt), client)
    }
}

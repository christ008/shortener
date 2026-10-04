package uy.ct.shortener.security.internal

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver

/**
 * Refuses the `Bearer` scheme outright, for when every access token must be bound to its client's
 * key. A request under the `DPoP` scheme is not seen by this resolver, since it takes the DPoP
 * authentication path instead. Refusing is what stops a stolen token from being replayed as a
 * plain bearer token, which is the point of binding it (RFC 9449, section 7.2).
 */
class SenderConstrainedBearerTokenResolver : BearerTokenResolver {

    private val delegate = DefaultBearerTokenResolver()

    override fun resolve(request: HttpServletRequest): String? {
        if (delegate.resolve(request) == null) return null
        throw OAuth2AuthenticationException(
            OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Access tokens must be presented with the DPoP scheme", null),
        )
    }
}

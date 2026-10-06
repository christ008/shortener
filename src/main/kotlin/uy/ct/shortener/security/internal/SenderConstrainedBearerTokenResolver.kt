package uy.ct.shortener.security.internal

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver

/**
 * Refuses the `Bearer` scheme, for when every access token must be bound to its client's key.
 *
 * A request under the `DPoP` scheme never reaches this resolver.
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

package uy.ct.shortener.shortlink.internal.authorization

import org.aopalliance.intercept.MethodInvocation
import org.springframework.security.authorization.AuthorizationResult
import org.springframework.security.authorization.method.MethodAuthorizationDeniedHandler
import org.springframework.stereotype.Component
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLinkNotFoundException

/**
 * Turns a denied lookup of a link, whose first argument is its code, into
 * [ShortLinkNotFoundException] so a client cannot tell another client's link from a missing one.
 */
@Component
class NotFoundWhenDenied : MethodAuthorizationDeniedHandler {

    override fun handleDeniedInvocation(methodInvocation: MethodInvocation, authorizationResult: AuthorizationResult): Any? =
        throw ShortLinkNotFoundException(methodInvocation.arguments.first() as ShortCode)
}

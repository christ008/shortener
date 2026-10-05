package uy.ct.shortener.shortlink.internal.authorization

import org.springframework.security.access.prepost.PostAuthorize
import org.springframework.security.authorization.method.HandleAuthorizationDenied
import org.springframework.stereotype.Component
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import uy.ct.shortener.shortlink.ShortLinkRepository

/**
 * Loads a link only if the caller may manage it.
 *
 * - The check runs after the load, with `@PostAuthorize` against [ShortLinkPermissionEvaluator].
 * - Being denied is reported as the link not being found ([NotFoundWhenDenied]), so another
 *   client's link is indistinguishable from a missing one.
 * - A bean of its own, because method security only applies to calls made through a Spring proxy.
 */
@Component
class ManageableLinks(private val repository: ShortLinkRepository) {

    @PostAuthorize("hasPermission(returnObject, 'manage')")
    @HandleAuthorizationDenied(handlerClass = NotFoundWhenDenied::class)
    fun get(shortCode: ShortCode): ShortLink = repository.findByShortCode(shortCode).orThrow(shortCode)
}

package uy.ct.shortener.shortlink.internal.authorization

import org.springframework.security.authorization.method.HandleAuthorizationDenied
import org.springframework.security.access.prepost.PostAuthorize
import org.springframework.stereotype.Component
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import uy.ct.shortener.shortlink.ShortLinkRepository

/**
 * Finds a link only if the caller may manage it, checked after the load with `@PostAuthorize`
 * against [ShortLinkPermissionEvaluator]. Being denied is reported as the link not being found
 * ([NotFoundWhenDenied]), so another client's links are indistinguishable from missing ones. It is
 * a bean of its own because method security works on calls made through a Spring proxy.
 */
@Component
class ManageableLinks(private val repository: ShortLinkRepository) {

    @PostAuthorize("returnObject == null or hasPermission(returnObject, 'manage')")
    @HandleAuthorizationDenied(handlerClass = NotFoundWhenDenied::class)
    fun find(shortCode: ShortCode): ShortLink? = repository.findByShortCode(shortCode)
}

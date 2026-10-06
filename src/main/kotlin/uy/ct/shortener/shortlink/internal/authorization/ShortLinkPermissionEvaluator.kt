package uy.ct.shortener.shortlink.internal.authorization

import org.springframework.beans.factory.ObjectProvider
import org.springframework.security.access.PermissionEvaluator
import org.springframework.security.core.Authentication
import uy.ct.shortener.shortlink.Actor
import uy.ct.shortener.shortlink.ShortLink
import java.io.Serializable

/**
 * Spring Security's `hasPermission(link, 'manage')` rule for short links.
 *
 * - A client may manage the links it created.
 * - An administrator may manage any link.
 * - The scopes are resolved lazily.
 */
class ShortLinkPermissionEvaluator(private val scopes: ObjectProvider<ShortLinkScopes>) : PermissionEvaluator {

    override fun hasPermission(authentication: Authentication, targetDomainObject: Any?, permission: Any): Boolean =
        targetDomainObject is ShortLink && permission == MANAGE &&
            (targetDomainObject.isCreatedBy(Actor.Client(authentication.name)) || isAdmin(authentication))

    override fun hasPermission(authentication: Authentication, targetId: Serializable, targetType: String, permission: Any): Boolean = false

    private fun isAdmin(authentication: Authentication) =
        authentication.authorities.any { it.authority == scopes.getObject().admin }

    companion object {
        const val MANAGE = "manage"
    }
}

package uy.ct.shortener.shortlink.internal.authorization

import org.springframework.beans.factory.ObjectProvider
import org.springframework.security.access.PermissionEvaluator
import org.springframework.security.core.Authentication
import uy.ct.shortener.shortlink.ShortLink
import java.io.Serializable

/**
 * Spring Security's hook for rules about a particular domain object, used as
 * `hasPermission(link, 'manage')`. A client may manage the links it created, and an
 * administrator any link. It resolves the scopes lazily, because method security needs it while
 * the application context is still starting.
 */
class ShortLinkPermissionEvaluator(private val scopes: ObjectProvider<ShortLinkScopes>) : PermissionEvaluator {

    override fun hasPermission(authentication: Authentication, targetDomainObject: Any?, permission: Any): Boolean =
        targetDomainObject is ShortLink && permission == MANAGE &&
            (targetDomainObject.createdBy == authentication.name || authentication.authorities.any { it.authority == scopes.getObject().admin })

    override fun hasPermission(authentication: Authentication, targetId: Serializable, targetType: String, permission: Any): Boolean = false

    companion object {
        const val MANAGE = "manage"
    }
}

package uy.ct.shortener.shortlink.internal.authorization

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.TypeReference
import uy.ct.shortener.shortlink.Actor
import uy.ct.shortener.shortlink.CreatedByFilter

/**
 * Native-image hints for the method-security expressions.
 *
 * - Expressions such as `authentication.name`, `#createdBy.name` and `#filter.isLimitedTo(...)` call methods through
 *   the Spring Expression Language, which finds them by reflection on the runtime class.
 * - A native image keeps no such metadata unless it is registered. Without these hints every
 *   authorized call fails with a 500 instead of being decided.
 */
class AuthorizationRuntimeHints : RuntimeHintsRegistrar {

    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        listOf(
            TypeReference.of("org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken"),
            TypeReference.of("org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken"),
            TypeReference.of("org.springframework.security.authentication.AbstractAuthenticationToken"),
            TypeReference.of(CreatedByFilter.Anyone::class.java),
            TypeReference.of(CreatedByFilter.Only::class.java),
            TypeReference.of(Actor.Client::class.java),
        ).forEach { hints.reflection().registerType(it, MemberCategory.INVOKE_PUBLIC_METHODS) }
    }
}

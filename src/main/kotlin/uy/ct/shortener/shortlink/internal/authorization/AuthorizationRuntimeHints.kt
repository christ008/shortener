package uy.ct.shortener.shortlink.internal.authorization

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.TypeReference
import uy.ct.shortener.shortlink.Actor
import uy.ct.shortener.shortlink.CreatedByFilter

/**
 * Native-image hints that register for reflection the methods the method-security expressions call: `authentication.name`,
 * `#createdBy.name` and `#filter.isLimitedTo(...)`.
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

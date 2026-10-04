package uy.ct.shortener.shortlink.internal.authorization

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.TypeReference

/**
 * Native-image hints for the method-security expressions.
 *
 * Expressions such as `#createdBy == authentication.name` read `name` from the authentication
 * through the Spring Expression Language, which finds the getter by reflection on the runtime
 * class of the token. A native image keeps no such metadata unless it is registered, so without
 * these hints every authorized call fails with a 500 instead of being decided.
 */
class AuthorizationRuntimeHints : RuntimeHintsRegistrar {

    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        listOf(
            "org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken",
            "org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken",
            "org.springframework.security.authentication.AbstractAuthenticationToken",
        ).forEach { hints.reflection().registerType(TypeReference.of(it), MemberCategory.INVOKE_PUBLIC_METHODS) }
    }
}

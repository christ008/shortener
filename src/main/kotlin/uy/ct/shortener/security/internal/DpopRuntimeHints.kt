package uy.ct.shortener.security.internal

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.TypeReference

/**
 * Native-image hint that keeps DPoP support switched on.
 *
 * - Spring Security adds its DPoP filter only if `ClassUtils.isPresent` finds
 *   `DPoPProofJwtDecoderFactory`.
 * - In a native image that lookup finds a class only if it is registered for reflection. Without
 *   this hint the check fails quietly, the filter is left out, and every request with a DPoP token
 *   is turned away with a 401 that gives no reason.
 */
class DpopRuntimeHints : RuntimeHintsRegistrar {

    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        hints.reflection().registerType(
            TypeReference.of("org.springframework.security.oauth2.jwt.DPoPProofJwtDecoderFactory"),
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
        )
    }
}

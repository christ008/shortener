package uy.ct.shortener.security.internal

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.TypeReference

/** Native-image hint that registers `DPoPProofJwtDecoderFactory` for reflection, so Spring Security keeps its DPoP filter. */
class DpopRuntimeHints : RuntimeHintsRegistrar {

    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        hints.reflection().registerType(
            TypeReference.of("org.springframework.security.oauth2.jwt.DPoPProofJwtDecoderFactory"),
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
        )
    }
}

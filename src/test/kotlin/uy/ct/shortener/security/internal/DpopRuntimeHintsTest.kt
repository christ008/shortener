package uy.ct.shortener.security.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates
import org.springframework.aot.hint.TypeReference

class DpopRuntimeHintsTest {

    @Test
    fun `registers the class whose presence switches on Spring Security's DPoP support`() {
        val hints = RuntimeHints().also { DpopRuntimeHints().registerHints(it, javaClass.classLoader) }

        assertThat(
            RuntimeHintsPredicates.reflection()
                .onType(TypeReference.of("org.springframework.security.oauth2.jwt.DPoPProofJwtDecoderFactory")),
        ).accepts(hints)
    }
}

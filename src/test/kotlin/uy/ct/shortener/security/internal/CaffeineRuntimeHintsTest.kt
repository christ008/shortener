package uy.ct.shortener.security.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates
import org.springframework.aot.hint.TypeReference

class CaffeineRuntimeHintsTest {

    private val hints = RuntimeHints().also { CaffeineRuntimeHints().registerHints(it, javaClass.classLoader) }

    private fun reflectiveConstructorOf(name: String) =
        RuntimeHintsPredicates.reflection()
            .onType(TypeReference.of("com.github.benmanes.caffeine.cache.$name"))
            .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)

    @Test
    fun `registers the generated class that the rate limiter's cache configuration needs`() {
        assertThat(reflectiveConstructorOf("SSMSA")).accepts(hints)
    }

    @Test
    fun `registers every generated cache and entry class, not just the ones this app uses`() {
        listOf("SS", "SSA", "SSMS", "PSA", "PSMS").forEach { assertThat(reflectiveConstructorOf(it)).accepts(hints) }
    }

    @Test
    fun `registers the classes whose fields are read through var handles`() {
        assertThat(
            RuntimeHintsPredicates.reflection()
                .onType(TypeReference.of("com.github.benmanes.caffeine.cache.StripedBuffer"))
                .withMemberCategory(MemberCategory.ACCESS_DECLARED_FIELDS),
        ).accepts(hints)
    }
}

package uy.ct.shortener.shortlink.internal.authorization

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

class AuthorizationRuntimeHintsTest {

    @Test
    fun `lets expressions read the name of the token that authenticated the call`() {
        val hints = RuntimeHints().also { AuthorizationRuntimeHints().registerHints(it, javaClass.classLoader) }

        assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(JwtAuthenticationToken::class.java, "getName")).accepts(hints)
    }
}

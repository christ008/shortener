package uy.ct.shortener.security.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant
import kotlin.test.assertIs

/**
 * A token with the administrator scope is accepted when it shows a sign-in with a second factor (an `acr` of level 2 or higher, or a name the
 * realm gave such a level), or when its client is a service account that the operator listed. Anything else with the scope is refused, and a token without it is not looked at.
 */
class AdminAssuranceTest {

    private val assurance = AdminAssurance("shortlinks:admin", 2, emptyList(), setOf("admin-client"))

    private val admin = listOf(SimpleGrantedAuthority("shortlinks:admin"))

    private val reader = listOf(SimpleGrantedAuthority("shortlinks:read"))

    private fun jwt(azp: String?, acr: String?) = Jwt.withTokenValue("t")
        .header("alg", "RS256")
        .claims { claims ->
            claims["sub"] = "x"
            azp?.let { claims["azp"] = it }
            acr?.let { claims["acr"] = it }
        }
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
        .build()

    @Test
    fun `a person who signed in with a second factor is an administrator, at level 2 or any higher one`() {
        assurance.require(jwt("shortener-ui", "2"), admin)
        assurance.require(jwt("shortener-ui", "3"), admin)
        assurance.require(jwt("shortener-ui", "10"), admin)
    }

    @Test
    fun `a name the realm gave a level counts when it was listed, and no other name does`() {
        val named = AdminAssurance("shortlinks:admin", 2, listOf("mfa"), emptySet())

        named.require(jwt("shortener-ui", "mfa"), admin)
        listOf("pwd", "silver", "two").forEach {
            assertThatThrownBy { named.require(jwt("shortener-ui", it), admin) }.describedAs(it).isInstanceOf(InsufficientUserAuthenticationException::class.java)
        }
        assertThat(assertIs<InsufficientUserAuthenticationException>(runCatching { named.require(jwt("shortener-ui", "1"), admin) }.exceptionOrNull()).acrValues)
            .describedAs("it asks for the name the realm uses").containsExactly("mfa")
    }

    @Test
    fun `a person who signed in with a password alone, or whose sign-in says nothing, is refused with what would do`() {
        listOf("1", "0", "-1", "pwd", "2.5", "two", "", null).forEach { acr ->
            assertThatThrownBy { assurance.require(jwt("shortener-ui", acr), admin) }
                .describedAs("acr $acr")
                .isInstanceOfSatisfying(InsufficientUserAuthenticationException::class.java) {
                    assertThat(it.acrValues).describedAs("the level to ask for").containsExactly("2")
                    assertThat(it.error.errorCode).isEqualTo("insufficient_user_authentication")
                }
        }
    }

    @Test
    fun `a service account that was listed needs no second factor, and one that was not does`() {
        assurance.require(jwt("admin-client", null), admin)
        assertThatThrownBy { assurance.require(jwt("other-client", null), admin) }.isInstanceOf(InsufficientUserAuthenticationException::class.java)
        assertThatThrownBy { assurance.require(jwt(null, null), admin) }.isInstanceOf(InsufficientUserAuthenticationException::class.java)
    }

    @Test
    fun `a token without the administrator scope is not looked at`() {
        assurance.require(jwt("shortener-ui", "1"), reader)
        assurance.require(jwt("shortener-ui", null), emptyList())
    }

    @Test
    fun `with no client listed and no level reachable, nobody holds the scope`() {
        val none = AdminAssurance("shortlinks:admin", Int.MAX_VALUE, emptyList(), emptySet())

        assertThatThrownBy { none.require(jwt("admin-client", "2"), admin) }.isInstanceOf(InsufficientUserAuthenticationException::class.java)
    }
}

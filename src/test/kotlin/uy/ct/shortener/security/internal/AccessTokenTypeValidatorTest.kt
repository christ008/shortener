package uy.ct.shortener.security.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant

/**
 * A token is an access token when its header says `at+jwt`, with or without the `application/` prefix, or says `JWT` and the
 * token's own `typ` claim is one the identity provider gives its access tokens. Every other kind it signs is refused.
 */
class AccessTokenTypeValidatorTest {

    private val validator = AccessTokenTypeValidator("at+jwt", setOf("Bearer", "DPoP"))

    private fun jwt(header: String?, claim: String? = null) = Jwt.withTokenValue("t")
        .headers { if (header != null) it["typ"] = header else it["alg"] = "RS256" }
        .claims { it["sub"] = "x"; if (claim != null) it["typ"] = claim }
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
        .build()

    private fun accepts(header: String?, claim: String? = null, validator: AccessTokenTypeValidator = this.validator) =
        !validator.validate(jwt(header, claim)).hasErrors()

    @Test
    fun `the header at+jwt is enough, however it is spelled`() {
        assertThat(accepts("at+jwt")).isTrue
        assertThat(accepts("AT+JWT")).isTrue
        assertThat(accepts("application/at+jwt")).isTrue
    }

    @Test
    fun `the header JWT needs a claim that marks an access token`() {
        assertThat(accepts("JWT", "Bearer")).isTrue
        assertThat(accepts("JWT", "DPoP")).isTrue
        listOf("ID", "Refresh", "Offline", "Logout", null).forEach { assertThat(accepts("JWT", it)).describedAs("claim $it").isFalse }
    }

    @Test
    fun `a claim does not make a token of another header an access token, and no header is not one`() {
        assertThat(accepts("id_token+jwt", "Bearer")).isFalse
        assertThat(accepts("logout+jwt", "DPoP")).isFalse
        assertThat(accepts(null, "Bearer")).isFalse
    }

    @Test
    fun `without claim types only the header counts`() {
        val strict = AccessTokenTypeValidator("at+jwt", emptySet())

        assertThat(accepts("at+jwt", validator = strict)).isTrue
        assertThat(accepts("JWT", "Bearer", strict)).isFalse
    }

    @Test
    fun `a refusal is an invalid_token error`() {
        val result = validator.validate(jwt("JWT", "ID"))

        assertThat(result.errors.single().errorCode).isEqualTo("invalid_token")
    }
}

package uy.ct.shortener.security.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.assertFailsWith

class ApiKeyAuthenticatorTest {

    private fun sha256(value: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private val authenticator = ApiKeyAuthenticator(
        mapOf("ci" to sha256("key-for-ci"), "mobile" to sha256("key-for-mobile").uppercase()),
    )

    @Test
    fun `resolves a key to the name of its client`() {
        assertThat(authenticator.authenticate("key-for-ci")).isEqualTo("ci")
        assertThat(authenticator.authenticate("key-for-mobile")).isEqualTo("mobile")
    }

    @Test
    fun `rejects keys that are unknown, altered or empty`() {
        listOf("key-for-cI", "key-for-ci ", "", "ci", sha256("key-for-ci")).forEach {
            assertThat(authenticator.authenticate(it)).describedAs("key '%s'", it).isNull()
        }
    }

    @Test
    fun `rejects every key when none are configured`() {
        assertThat(ApiKeyAuthenticator(emptyMap()).authenticate("key-for-ci")).isNull()
    }

    @Test
    fun `refuses configuration that is not a hex sha-256 digest`() {
        listOf("plaintext-key", "abc123", sha256("x").dropLast(1), sha256("x") + "0").forEach {
            assertFailsWith<IllegalArgumentException>("expected '$it' to be rejected") {
                ApiKeyAuthenticator(mapOf("client" to it))
            }
        }
    }
}

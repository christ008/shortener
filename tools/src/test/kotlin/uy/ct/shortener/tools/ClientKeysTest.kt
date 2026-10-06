package uy.ct.shortener.tools

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.util.Base64URL
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The keys of the dev clients are what `dev-setup` writes and what the realm trusts, so a key that the identity provider cannot
 * read, or a private one that does not match its public one, is a setup that fails at the first sign-in. They are read back here
 * by a library that is not the one that wrote them.
 */
class ClientKeysTest {

    @TempDir
    lateinit var directory: Path

    private fun fileWith(text: String): Path = Files.writeString(directory.resolve("key.jwk.json"), text)

    @Test
    fun `a generated key is a P-256 JWK for ES256 whose public half is the public part of the private one`() {
        val key = ClientKeys.generate("demo-client")

        val private = ECKey.parse(key.privateJwk)
        val public = ECKey.parse(key.publicJwk)

        assertThat(private.isPrivate).isTrue()
        assertThat(private.curve).isEqualTo(Curve.P_256)
        assertThat(private.keyID).isEqualTo("demo-client-key-1")
        assertThat(private.algorithm.name).isEqualTo("ES256")
        assertThat(private.keyUse.identifier()).isEqualTo("sig")
        assertThat(public.isPrivate).describedAs("the realm gets no private part").isFalse()
        assertThat(public.toJSONString()).isEqualTo(private.toPublicJWK().toJSONString())
        assertThat(parseJson(key.publicJwk)).doesNotContainKey("d")
    }

    @Test
    fun `every coordinate and scalar is 32 bytes, however many leading zeros it would have had`() {
        repeat(300) {
            val key = parseJson(ClientKeys.generate("c").privateJwk)

            listOf("x", "y", "d").forEach { member -> assertThat(Base64URL(key.getValue(member) as String).decode()).hasSize(32) }
        }
    }

    @Test
    fun `two keys are not the same key`() {
        assertThat(ClientKeys.generate("c").privateJwk).isNotEqualTo(ClientKeys.generate("c").privateJwk)
    }

    @Test
    fun `a key read from a file signs what the public half verifies`() {
        val key = ClientKeys.generate("demo-client")
        val signed = DpopCalls.jws(ClientKeys.readPrivate(fileWith(key.privateJwk)), linkedMapOf("alg" to "ES256"), linkedMapOf("sub" to "demo-client"))

        val jwt = com.nimbusds.jwt.SignedJWT.parse(signed)

        assertThat(jwt.verify(ECDSAVerifier(ECKey.parse(key.publicJwk)))).isTrue()
        assertThat(jwt.jwtClaimsSet.subject).isEqualTo("demo-client")
    }

    @Test
    fun `a file that is not there, not JSON, not a P-256 key or has no private part is a failure that names the file`() {
        val missing = directory.resolve("missing.json")
        assertThatThrownBy { ClientKeys.readPrivate(missing) }.isInstanceOf(Failure::class.java).hasMessage("could not read the key file $missing")

        val garbage = fileWith("not json")
        assertThatThrownBy { ClientKeys.readPrivate(garbage) }.isInstanceOf(Failure::class.java).hasMessageContaining("$garbage is not JSON")

        val other = fileWith("""{"kty":"EC","crv":"P-384","d":"AAAA"}""")
        assertThatThrownBy { ClientKeys.readPrivate(other) }.isInstanceOf(Failure::class.java).hasMessageContaining("is not a private P-256 JWK")

        val publicOnly = fileWith(ClientKeys.generate("c").publicJwk)
        assertThatThrownBy { ClientKeys.readPrivate(publicOnly) }.isInstanceOf(Failure::class.java).hasMessageContaining("is not a private P-256 JWK")
    }
}

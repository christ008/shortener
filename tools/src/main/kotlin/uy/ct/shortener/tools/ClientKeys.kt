package uy.ct.shortener.tools

import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.util.Base64

/**
 * The P-256 keys of the dev clients, as JWKs, made and read with what the JDK has.
 *
 * - The tools do not use `deploy/keycloak/DpopClient.java`: that file is the reference for people outside the project, and what
 *   the tools need of it (a key, a signature, a proof) is written again here, so that changing one never breaks the other.
 * - [generate] is what `dev-setup` writes for each client: the private JWK, and the public one that goes in the realm. They have
 *   the members the reference client's `keygen` prints, so either can read what the other made.
 * - [readPrivate] reads such a file back, to sign with it.
 */
object ClientKeys {

    private val JSON = JsonMapper.builder().build()

    private val BASE64 = Base64.getUrlEncoder().withoutPadding()

    private val BASE64_DECODER = Base64.getUrlDecoder()

    class ClientKey(val privateJwk: String, val publicJwk: String)

    fun generate(client: String): ClientKey {
        val key = newKeyPair()
        val public = linkedMapOf<String, Any>("kty" to "EC", "crv" to "P-256", "kid" to "$client-key-1", "alg" to "ES256", "use" to "sig")
        public.putAll(coordinates(key))
        val private = LinkedHashMap(public)
        private["d"] = base64((key.private as ECPrivateKey).s)
        return ClientKey(JSON.writeValueAsString(private), JSON.writeValueAsString(public))
    }

    /** A key for a proof: one for each call, so that no token is bound to a key that anyone else has. */
    fun newKeyPair(): KeyPair =
        try {
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        } catch (problem: GeneralSecurityException) {
            throw Failure("this JDK cannot make P-256 keys: $problem")
        }

    /** The members of the public JWK of [key] that a proof carries in its header. */
    fun publicJwk(key: KeyPair): Map<String, Any> = linkedMapOf<String, Any>("kty" to "EC", "crv" to "P-256").also { it.putAll(coordinates(key)) }

    /** Only `d` is needed to sign, but the file must be a P-256 key to be one for ES256. */
    fun readPrivate(file: Path): PrivateKey {
        if (!Files.isReadable(file)) throw Failure("could not read the key file $file")
        val jwk = try {
            JSON.readTree(Files.readString(file))
        } catch (notJson: JacksonException) {
            throw Failure("$file is not JSON: ${notJson.originalMessage}")
        }
        val d = jwk.path("d").stringValue(null)
        if (jwk.path("kty").stringValue(null) != "EC" || jwk.path("crv").stringValue(null) != "P-256" || d == null) {
            throw Failure("$file is not a private P-256 JWK (it needs kty EC, crv P-256 and d)")
        }
        return try {
            val curve = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
            KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, BASE64_DECODER.decode(d)), curve))
        } catch (problem: GeneralSecurityException) {
            throw Failure("could not read the key in $file: ${problem.message}")
        } catch (problem: IllegalArgumentException) {
            throw Failure("could not read the key in $file: ${problem.message}")
        }
    }

    private fun coordinates(key: KeyPair): Map<String, String> {
        val point = (key.public as ECPublicKey).w
        return linkedMapOf("x" to base64(point.affineX), "y" to base64(point.affineY))
    }

    /** A coordinate or a scalar of P-256 as the 32 bytes a JWK wants: BigInteger drops leading zeros and may add a sign byte. */
    private fun base64(value: BigInteger): String {
        val bytes = value.toByteArray()
        val fixed = ByteArray(32)
        val length = minOf(bytes.size, 32)
        System.arraycopy(bytes, bytes.size - length, fixed, 32 - length, length)
        return BASE64.encodeToString(fixed)
    }
}

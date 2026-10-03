package uy.ct.shortener.security.internal

import java.security.MessageDigest
import java.util.HexFormat

/**
 * Resolves a presented API key to its client name by comparing SHA-256 digests, so plaintext
 * keys are never stored. Keys are random and high-entropy, which is why a fast hash is enough.
 * Every configured digest is compared in constant time, with no early exit on a match.
 *
 * @throws IllegalArgumentException if a configured value is not a 64-character hex SHA-256
 */
class ApiKeyAuthenticator(apiKeyHashes: Map<String, String>) {

    private val digests: List<Pair<String, ByteArray>> = apiKeyHashes.map { (name, hex) ->
        require(HEX_SHA256.matches(hex)) { "API key '$name' must be configured as a hex SHA-256 digest" }
        name to HexFormat.of().parseHex(hex)
    }

    fun authenticate(presentedKey: String): String? {
        val presented = MessageDigest.getInstance("SHA-256").digest(presentedKey.toByteArray())
        return digests.fold<Pair<String, ByteArray>, String?>(null) { match, (name, digest) ->
            if (MessageDigest.isEqual(digest, presented)) name else match
        }
    }

    private companion object {
        val HEX_SHA256 = Regex("[0-9a-fA-F]{64}")
    }
}

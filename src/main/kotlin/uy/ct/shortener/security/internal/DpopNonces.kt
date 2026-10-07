package uy.ct.shortener.security.internal

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The nonces a client must put in its DPoP proofs (RFC 9449, section 9), made without storing any.
 *
 * - [current] is the nonce to hand out, or null when nonces are not required.
 * - [accepts] says whether a proof's nonce is acceptable: the current one or the one before it. Null, blank and
 *   anything not made by [HmacDpopNonces] with the same secret are not.
 */
sealed interface DpopNonces {

    fun current(): String?

    fun accepts(nonce: String?): Boolean

    companion object {
        fun from(settings: SecurityProperties.Nonce, clock: Clock = Clock.systemUTC()): DpopNonces =
            if (settings.enabled) HmacDpopNonces(settings.secret.ifBlank { null }?.toByteArray() ?: randomSecret(), settings.interval, clock) else NoDpopNonces

        private fun randomSecret(): ByteArray = ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes)

        private const val SECRET_BYTES = 32
    }
}

/** Nonces are not required: no nonce is handed out and any proof is acceptable. */
data object NoDpopNonces : DpopNonces {
    override fun current(): String? = null

    override fun accepts(nonce: String?): Boolean = true
}

/**
 * A nonce is the number of the [interval] it was made in, and a keyed hash (HMAC-SHA-256) of that number, so any instance that
 * has the secret can check one it did not make. A nonce is good for the interval it was made in and the next, which is between
 * one and two intervals. The secret is random for each process unless given, which is only right for a single instance.
 */
class HmacDpopNonces(private val secret: ByteArray, private val interval: Duration, private val clock: Clock = Clock.systemUTC()) : DpopNonces {

    init {
        require(secret.isNotEmpty()) { "The nonce secret is empty" }
        require(!interval.isZero && !interval.isNegative) { "The nonce interval must be positive" }
    }

    override fun current(): String = nonceOf(intervalNow())

    override fun accepts(nonce: String?): Boolean {
        val bytes = nonce?.let { decode(it) } ?: return false
        if (bytes.size != NONCE_BYTES) return false
        val number = ByteBuffer.wrap(bytes, 0, Long.SIZE_BYTES).long
        val now = intervalNow()
        // `MessageDigest.isEqual` compares in constant time, so a wrong nonce does not say how much of it matched.
        return (number == now || number == now - 1) && MessageDigest.isEqual(bytes, bytesOf(number))
    }

    private fun intervalNow(): Long = clock.millis() / interval.toMillis()

    private fun nonceOf(number: Long): String = ENCODER.encodeToString(bytesOf(number))

    private fun bytesOf(number: Long): ByteArray {
        val counter = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(number).array()
        val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(secret, ALGORITHM)) }.doFinal(counter)
        return counter + mac.copyOf(MAC_BYTES)
    }

    private fun decode(text: String): ByteArray? =
        try {
            DECODER.decode(text)
        } catch (notBase64: IllegalArgumentException) {
            null
        }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val MAC_BYTES = 16
        const val NONCE_BYTES = Long.SIZE_BYTES + MAC_BYTES
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}

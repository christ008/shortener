package uy.ct.shortener.shortlink.internal

import org.springframework.stereotype.Component
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortCodeGenerator
import java.security.SecureRandom

/**
 * Generates uniformly random codes with [SecureRandom], so they can't be guessed to
 * enumerate links.
 */
@Component
class RandomShortCodeGenerator(
    private val random: SecureRandom = SecureRandom(),
) : ShortCodeGenerator {

    override fun generate(): ShortCode {
        val code = CharArray(ShortCode.GENERATED_LENGTH) { ShortCode.ALPHABET[random.nextInt(ShortCode.ALPHABET.length)] }
        return ShortCode(String(code))
    }
}

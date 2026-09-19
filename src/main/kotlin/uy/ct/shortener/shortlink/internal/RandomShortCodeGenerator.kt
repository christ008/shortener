package uy.ct.shortener.shortlink.internal

import org.springframework.stereotype.Component
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortCodeGenerator
import java.security.SecureRandom

/** [SecureRandom], not [kotlin.random.Random]: codes shouldn't be predictable enough to enumerate links by guessing. */
@Component
class RandomShortCodeGenerator(
    private val random: SecureRandom = SecureRandom(),
) : ShortCodeGenerator {

    override fun generate(): ShortCode {
        val code = CharArray(ShortCode.LENGTH) { ShortCode.ALPHABET[random.nextInt(ShortCode.ALPHABET.length)] }
        return ShortCode(String(code))
    }
}

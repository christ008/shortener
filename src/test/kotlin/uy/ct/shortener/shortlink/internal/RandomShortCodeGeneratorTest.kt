package uy.ct.shortener.shortlink.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uy.ct.shortener.shortlink.ShortCode
import java.security.SecureRandom

class RandomShortCodeGeneratorTest {

    private val generator = RandomShortCodeGenerator()

    @Test
    fun `generates codes of the configured length drawn only from the alphabet`() {
        repeat(1_000) {
            val code = generator.generate().value

            assertThat(code).hasSize(ShortCode.LENGTH)
            assertThat(code.all { it in ShortCode.ALPHABET }).isTrue
        }
    }

    @Test
    fun `does not repeat itself`() {
        val codes = List(1_000) { generator.generate() }

        assertThat(codes.toSet()).hasSize(1_000)
    }

    @Test
    fun `is deterministic for a given random source`() {
        fun seeded() = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) }

        val first = RandomShortCodeGenerator(seeded()).generate()
        val second = RandomShortCodeGenerator(seeded()).generate()

        assertThat(first).isEqualTo(second)
    }
}

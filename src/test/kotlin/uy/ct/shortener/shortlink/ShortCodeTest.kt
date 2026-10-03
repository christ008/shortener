package uy.ct.shortener.shortlink

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class ShortCodeTest {

    @Test
    fun `accepts 3 to 32 letters, digits, hyphens and underscores`() {
        listOf("abc", "aB3xQ9z", "my-promo_2026", "A".repeat(32)).forEach {
            assertThat(ShortCode(it).value).isEqualTo(it)
        }
    }

    @Test
    fun `rejects values outside the format`() {
        listOf("", "ab", "a".repeat(33), "has space", "dot.ted", "slash/es", "ünï", "abc\n").forEach {
            assertFailsWith<IllegalArgumentException>("expected '$it' to be rejected") { ShortCode(it) }
        }
    }
}

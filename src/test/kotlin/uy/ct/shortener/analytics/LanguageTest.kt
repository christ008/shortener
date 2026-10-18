package uy.ct.shortener.analytics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * What an `Accept-Language` header becomes: the ISO 639-1 code of the range the browser weighs highest, or [Language.Unknown]. The
 * headers are the kinds browsers send, and malformed ones.
 */
class LanguageTest {

    private fun of(header: String) = Language.of(header)

    @Test
    fun `the first range wins when the weights say so, and only its language is kept`() {
        assertThat(of("es-UY,es;q=0.9,en-US;q=0.8,en;q=0.7")).isEqualTo(Language.Code("es"))
        assertThat(of("en-US,en;q=0.5")).isEqualTo(Language.Code("en"))
        assertThat(of("pt-BR")).isEqualTo(Language.Code("pt"))
        assertThat(of("FR-ca")).isEqualTo(Language.Code("fr"))
    }

    @Test
    fun `the highest weight wins wherever it is, and the first of equal ones`() {
        assertThat(of("en;q=0.5, de;q=0.9, fr;q=0.7")).isEqualTo(Language.Code("de"))
        assertThat(of("it;q=0.8, es;q=0.8")).isEqualTo(Language.Code("it"))
        assertThat(of("ja;Q=0.3, ko")).isEqualTo(Language.Code("ko"))
        assertThat(of("de;q=1.0, en")).isEqualTo(Language.Code("de"))
    }

    @Test
    fun `a weight of zero never wins, and a weight that cannot be read counts as zero`() {
        assertThat(of("fr;q=0, en;q=0.1")).isEqualTo(Language.Code("en"))
        assertThat(of("fr;q=0")).isEqualTo(Language.Unknown)
        assertThat(of("fr;q=lots, en;q=0.2")).isEqualTo(Language.Code("en"))
        assertThat(of("fr;q=2, en;q=0.2")).isEqualTo(Language.Code("en"))
        assertThat(of("fr;q=1.001, en;q=0.2")).isEqualTo(Language.Code("en"))
    }

    @Test
    fun `a range with no language never wins, whatever its weight`() {
        assertThat(of(";q=0.9, en;q=0.5")).isEqualTo(Language.Code("en"))
    }

    @Test
    fun `a parameter other than the weight is ignored`() {
        assertThat(of("nl;level=1")).isEqualTo(Language.Code("nl"))
    }

    @Test
    fun `the withdrawn codes count as the ones that replaced them`() {
        assertThat(of("iw")).isEqualTo(Language.Code("he"))
        assertThat(of("in-ID")).isEqualTo(Language.Code("id"))
        assertThat(of("ji")).isEqualTo(Language.Code("yi"))
    }

    @Test
    fun `no header, any language, a code that is not ISO 639-1 or a header that cannot be read is unknown`() {
        listOf("", "   ", "*", "*;q=0.5", "xx", "fil", "zzz-ZZ", "en_US", ";q=0.9", ",,,", "123", "e").forEach {
            assertThat(of(it)).describedAs("'$it'").isEqualTo(Language.Unknown)
        }
    }

    @Test
    fun `reads only the start of a very long header`() {
        assertThat(of("de," + "x".repeat(10_000))).isEqualTo(Language.Code("de"))
    }

    @Test
    fun `a code is never built from something that is not one`() {
        assertFailsWith<IllegalArgumentException> { Language.Code("xx") }
        assertFailsWith<IllegalArgumentException> { Language.Code("ES") }
        assertFailsWith<IllegalArgumentException> { Language.Code("(unknown)") }
    }
}

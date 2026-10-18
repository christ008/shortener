package uy.ct.shortener.analytics.internal.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uy.ct.shortener.analytics.Language
import uy.ct.shortener.analytics.Referrer

/** A referrer and a language read back from `link_click_daily` as what was written, and the words in parentheses never collide. */
class StoredDimensionTest {

    @Test
    fun `every kind of referrer reads back as itself`() {
        listOf(Referrer.Direct, Referrer.Other, Referrer.Host("t.co"), Referrer.Host("[2001:db8::1]")).forEach {
            assertThat(StoredDimension.referrer(StoredDimension.of(it))).isEqualTo(it)
        }
    }

    @Test
    fun `every kind of language reads back as itself`() {
        listOf(Language.Unknown, Language.Code("es")).forEach {
            assertThat(StoredDimension.language(StoredDimension.of(it))).isEqualTo(it)
        }
    }

    @Test
    fun `the words for no host and no language are not host names or codes`() {
        assertThat(listOf(Referrer.Direct, Referrer.Other).map { StoredDimension.of(it) }).containsExactly("(direct)", "(other)")
        assertThat(StoredDimension.of(Language.Unknown)).isEqualTo("(unknown)")
        assertThat(Referrer.of("https://(direct)/")).isEqualTo(Referrer.Direct)
    }
}

package uy.ct.shortener.analytics.internal.persistence

import uy.ct.shortener.analytics.Language
import uy.ct.shortener.analytics.Referrer

/**
 * How a referrer and a language are written in `link_click_daily`, and read back.
 *
 * - A host name or an ISO 639-1 code is stored as it is. [Referrer.Direct], [Referrer.Other] and [Language.Unknown] are words in
 *   parentheses, which no host name or code can be, so a stored value reads back as what was written.
 */
internal object StoredDimension {

    const val REFERRER = "referrer"

    const val LANGUAGE = "language"

    private const val DIRECT = "(direct)"

    private const val OTHER = "(other)"

    private const val UNKNOWN = "(unknown)"

    fun of(referrer: Referrer): String = when (referrer) {
        Referrer.Direct -> DIRECT
        Referrer.Other -> OTHER
        is Referrer.Host -> referrer.name
    }

    fun of(language: Language): String = when (language) {
        Language.Unknown -> UNKNOWN
        is Language.Code -> language.value
    }

    fun referrer(value: String): Referrer = when (value) {
        DIRECT -> Referrer.Direct
        OTHER -> Referrer.Other
        else -> Referrer.Host(value)
    }

    fun language(value: String): Language = when (value) {
        UNKNOWN -> Language.Unknown
        else -> Language.Code(value)
    }
}

package uy.ct.shortener.analytics

import java.util.Locale

/**
 * The language a visitor's browser asks for first, from its `Accept-Language` header: a [Code] of ISO 639-1, or [Unknown].
 *
 * - The range with the highest weight wins, and the first of equal ones. A weight of zero means "not this one" and never wins.
 * - Only the primary subtag is kept, lowercased: `es-UY` is `es`. The region would say more about where the visitor is.
 * - A code that is not one of the about 190 of ISO 639-1, `*`, or a header that cannot be read is [Unknown]. So the codes a link
 *   can collect are bounded without a cap. The withdrawn codes `iw`, `in` and `ji` count as `he`, `id` and `yi`.
 * - [of] reads the header by hand rather than with `Locale.LanguageRange.parse`, which costs about three times as much and throws
 *   on an empty header, in the thread of every redirect.
 */
sealed interface Language {

    data object Unknown : Language

    data class Code(val value: String) : Language {
        init {
            require(value in CODES) { "not an ISO 639-1 code: '$value'" }
        }
    }

    companion object {

        private const val MAX_HEADER = 256

        private val CODES: Set<String> = Locale.getISOLanguages().toHashSet()

        private val WITHDRAWN = mapOf("iw" to "he", "in" to "id", "ji" to "yi")

        fun of(header: String): Language {
            var best = ""
            var bestWeight = 0.0
            for (item in header.take(MAX_HEADER).split(',')) {
                val range = item.substringBefore(';').trim()
                val weight = weightOf(item)
                if (range.isNotEmpty() && weight > bestWeight) {
                    best = range
                    bestWeight = weight
                }
            }
            val primary = best.substringBefore('-').lowercase().let { WITHDRAWN.getOrDefault(it, it) }
            return if (primary in CODES) Code(primary) else Unknown
        }

        private fun weightOf(item: String): Double {
            val parameter = item.substringAfter(';', "").trim()
            if (parameter.isEmpty()) return 1.0
            val value = parameter.removePrefix("q=").removePrefix("Q=")
            return if (value == parameter) 1.0 else value.trim().toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: 0.0
        }
    }
}
